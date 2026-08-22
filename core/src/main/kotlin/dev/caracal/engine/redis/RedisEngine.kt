package dev.caracal.engine.redis

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.redis.RedisSession
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.CancellationSupport
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionForm
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.DriverProvider
import dev.caracal.engine.api.DriverRequirement
import dev.caracal.engine.api.EngineCapabilities
import dev.caracal.engine.api.EngineFamily
import dev.caracal.engine.api.EngineId
import dev.caracal.engine.api.FormField
import dev.caracal.engine.api.FormSection
import dev.caracal.engine.api.IntentClassifier
import dev.caracal.engine.api.NamespaceModel
import dev.caracal.engine.api.QuoteStyle
import dev.caracal.engine.api.ReadOnlyEnforcement
import dev.caracal.engine.api.RowIdentitySupport
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.SecretKind
import dev.caracal.engine.api.ServerVersion
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.api.TlsConfig
import dev.caracal.engine.api.TransactionSupport
import dev.caracal.engine.api.ValidationIssue
import java.time.Instant

/**
 * Redis, as the SPI sees it.
 *
 * The capabilities are where Redis earns its place as the second engine rather than
 * the second copy of the first: it is the one that says [ReadOnlyEnforcement.COMMAND_GUARD_ONLY]
 * and [CancellationSupport.CLIENT_ABANDON], and both of those are claims the product
 * makes elsewhere in stronger terms. A read-only PostgreSQL connection is held to it
 * by the server. A read-only Redis connection is held to it by an allowlist in this
 * process. Presenting the two with the same affordances would be saying something
 * untrue, which is exactly what a capability model is for.
 *
 * **A class rather than an object, and that is `ServiceLoader`'s doing.** A provider
 * on the class path is instantiated through a public no-argument constructor; the
 * static `provider()` method the loader also understands is honoured only for a
 * provider in a named module, which a desktop application shipped as one jar is not.
 * A Kotlin `object` has a private constructor and cannot be registered at all. So the
 * instance everything uses is the one [dev.caracal.core.engines.Engines] loaded, and
 * what stayed behind on the companion is what was never a member in the first place:
 * the identifier, the option key, and the capability record the session reads.
 */
class RedisEngine : DatabaseEngine {

    companion object {
        val ID = EngineId("redis")

        /** The ACL username, where the server has ACLs. Empty means legacy `AUTH`. */
        const val OPTION_USER = "user"

        val CAPABILITIES = EngineCapabilities(
            family = EngineFamily.KEY_VALUE,
            // Numbered databases, selected at connect time. No schemas inside them.
            namespaceModel = NamespaceModel.DATABASE,
            // `MULTI`/`EXEC` exists and is not what this offers; nothing here opens one.
            transactions = TransactionSupport.NONE,
            readOnlyEnforcement = ReadOnlyEnforcement.COMMAND_GUARD_ONLY,
            cancellation = CancellationSupport.CLIENT_ABANDON,
            rowIdentity = RowIdentitySupport.NONE,
            // Redis has no identifiers to quote — keys are opaque byte strings.
            identifierQuote = QuoteStyle.NONE,
            supportsMultipleResultSets = false,
            supportsExplain = false,
            supportsSchemaDiff = false,
            maxIdentifierLength = 0,
            defaultPort = 6379,
        )
    }

    override val id: EngineId = ID

    override val displayName: String = "Redis"

    /**
     * Null, and it will stay null.
     *
     * Not because Lettuce's licence permits bundling — it does, it is Apache-2.0 —
     * but because runtime provisioning is only expressible for JDBC drivers at all.
     * `java.sql.Driver` is a stable surface that can be reached reflectively across a
     * classloader boundary; a Lettuce client is not, and §8.2 makes that a rule
     * rather than an accident.
     */
    override val driverRequirement: DriverRequirement? = null

    override val capabilities: EngineCapabilities = CAPABILITIES

    override val intentClassifier: IntentClassifier = RedisIntent

    override val connectionForm: ConnectionForm = ConnectionForm(
        listOf(
            FormSection(
                title = "Server",
                fields = listOf(
                    FormField.Text(key = "host", label = "Host", required = true, default = "localhost"),
                    FormField.Number(
                        key = "port",
                        label = "Port",
                        required = true,
                        default = 6379,
                        range = 1..65535,
                    ),
                    FormField.Number(
                        key = "database",
                        label = "Database",
                        default = 0,
                        help = "The numbered database to select. Most servers have sixteen.",
                    ),
                ),
            ),
            FormSection(
                title = "Authentication",
                fields = listOf(
                    FormField.Text(
                        key = OPTION_USER,
                        label = "User",
                        help = "Leave empty unless the server uses Redis 6 ACLs.",
                    ),
                    FormField.Secret(key = "password", label = "Password", kind = SecretKind.PASSWORD),
                ),
            ),
            FormSection(
                title = "Security",
                fields = listOf(
                    FormField.Choice(
                        key = "tls",
                        label = "TLS",
                        options = listOf(
                            TlsMode.DISABLE.wire to "Disabled",
                            TlsMode.REQUIRE.wire to "Required, certificate checked",
                        ),
                        default = TlsMode.DISABLE.wire,
                    ),
                ),
            ),
        ),
    )

    override fun validate(descriptor: ConnectionDescriptor): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val target = descriptor.target
        if (target !is ConnectionTarget.Network) {
            issues += ValidationIssue("Redis connects to a host and a port.")
            return issues
        }
        if (target.host.isBlank()) {
            issues += ValidationIssue("Enter the server's host name or address.", field = "host")
        }
        if (target.port !in 1..65535) {
            issues += ValidationIssue("A port is a number between 1 and 65535.", field = "port")
        }
        val database = target.database
        if (!database.isNullOrBlank() && database.trim().toIntOrNull() == null) {
            issues += ValidationIssue(
                "A Redis database is a number, not a name.",
                field = "database",
            )
        }
        // v0.1 offers Redis one secure mode, and it is the one that checks the
        // certificate. Encrypt-but-do-not-verify defends against nothing an attacker
        // on the path cannot do anyway, and offering it means somebody picks it.
        val tls = descriptor.tls
        if (tls is TlsConfig.Required && !tls.verifyHostname) {
            issues += ValidationIssue(
                "Redis connections either check the server's certificate or do not use TLS.",
                field = "tls",
            )
        }
        return issues
    }

    override suspend fun connect(
        descriptor: ConnectionDescriptor,
        secrets: SecretBundle,
        policy: SessionPolicy,
        drivers: DriverProvider,
    ): DatabaseSession {
        validate(descriptor).firstOrNull()?.let {
            throw DbException(DbError.UnsupportedConfiguration(it.message))
        }
        val password = password(secrets)
        val session = RedisSession.open(config(descriptor, policy), password)
        return try {
            RedisEngineSession(session, ServerVersion.parse(session.serverVersion()))
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    /**
     * The descriptor, in the shape `RedisSession` and `RedisCommandGuard` still read.
     *
     * The environment and the read-only flag are not decoration here: the adapter
     * captures this config at open, and the guard reads both off it to decide whether
     * `FLUSHDB` needs a click, a typed phrase, or a refusal. That is core's decision
     * being made inside an engine, which §7 will undo — and until it does, this is
     * the honest way to wrap the code that exists rather than the code that should.
     */
    private fun config(descriptor: ConnectionDescriptor, policy: SessionPolicy): ConnectionConfig {
        val target = descriptor.target as ConnectionTarget.Network
        return ConnectionConfig(
            id = descriptor.id,
            name = descriptor.displayName,
            engine = Engine.REDIS,
            host = target.host,
            port = target.port,
            database = target.database.orEmpty(),
            username = descriptor.engineOptions[OPTION_USER].orEmpty(),
            tlsMode = tlsMode(descriptor.tls),
            environment = descriptor.environment,
            readOnly = policy.readOnly,
            color = null,
            // Not read by anything on the dialing path. A descriptor does not carry
            // one, and inventing a creation time for a connection that was saved
            // months ago would be worse than admitting this field is unused here.
            createdAt = Instant.EPOCH,
        )
    }

    private fun password(secrets: SecretBundle): Secret = when (secrets) {
        is SecretBundle.Password -> Secret(secrets.password.copyOf())
        is SecretBundle.UserPassword -> Secret(secrets.password.copyOf())
        SecretBundle.None -> Secret.EMPTY
        else -> throw DbException(
            DbError.UnsupportedConfiguration(
                "Redis connections authenticate with a password, or with an ACL user and a password.",
            ),
        )
    }

    private fun tlsMode(tls: TlsConfig): TlsMode = when (tls) {
        TlsConfig.Disabled -> TlsMode.DISABLE
        is TlsConfig.Required -> TlsMode.REQUIRE
    }
}
