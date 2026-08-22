package dev.caracal.engine.postgres

import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.postgres.PostgresConnectionConfig
import dev.caracal.core.postgres.PostgresSession
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

/**
 * PostgreSQL, as the SPI sees it.
 *
 * Nothing here knows anything the repository did not already know; it is the
 * existing dialing path with a declared shape around it. The capabilities are the
 * interesting part, because they are what replaces the nine `when (engine)`
 * switches: every one of those is a question the UI asks about an engine, and the
 * answer belongs in a record like this rather than in an arm of a `when` in a
 * composable.
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
class PostgresEngine : DatabaseEngine {

    companion object {
        val ID = EngineId("postgres")

        /** The connection's username. Not a secret: it is stored and displayed in the clear. */
        const val OPTION_USER = "user"

        /**
         * The lengths this engine will accept, which used to be constants in core.
         *
         * They are the engine's facts and not the form's: PostgreSQL truncates an
         * identifier at 63 bytes and a database name is one, so anything past this is
         * a paste rather than a name. Core's own cap is a backstop an order of
         * magnitude looser, and it does not know these.
         */
        const val MAX_DATABASE = 100

        const val MAX_USER = 100

        val CAPABILITIES = EngineCapabilities(
            family = EngineFamily.SQL,
            // A connection is bound to one database and browses the schemas inside it.
            // Reaching another database means another connection, which is PostgreSQL's
            // own rule and not a limitation of this browser.
            namespaceModel = NamespaceModel.SCHEMA,
            // Not EXPLICIT, and this is a statement about the product rather than about
            // the server. Every statement runs in a transaction the adapter opens and
            // closes; there is no begin/commit the user drives. When there is, this line
            // changes and a TransactionFacet appears alongside it.
            transactions = TransactionSupport.IMPLICIT,
            readOnlyEnforcement = ReadOnlyEnforcement.SESSION_SETTING,
            cancellation = CancellationSupport.OUT_OF_BAND,
            // `ctid` exists on every ordinary table, so a row without a primary key can
            // still be named. Nothing uses this yet — there is no editable grid — but it
            // is a fact about the engine and not about the feature.
            rowIdentity = RowIdentitySupport.PRIMARY_KEY_OR_PSEUDO,
            identifierQuote = QuoteStyle.DOUBLE_QUOTE,
            supportsMultipleResultSets = false,
            supportsExplain = true,
            supportsSchemaDiff = false,
            maxIdentifierLength = 63,
            defaultPort = 5432,
        )
    }

    override val id: EngineId = ID

    override val displayName: String = "PostgreSQL"

    override val capabilities: EngineCapabilities = CAPABILITIES

    /** BSD-2-Clause, so pgjdbc ships with the application and never needs a download. */
    override val driverRequirement: DriverRequirement? = null

    override val intentClassifier: IntentClassifier = PostgresIntent

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
                        default = 5432,
                        range = 1..65535,
                    ),
                    FormField.Text(key = "database", label = "Database", required = true),
                ),
            ),
            FormSection(
                title = "Authentication",
                fields = listOf(
                    FormField.Text(key = OPTION_USER, label = "User", required = true),
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
                            TlsMode.REQUIRE.wire to "Required, certificate not checked",
                            TlsMode.VERIFY_FULL.wire to "Required, certificate checked",
                        ),
                        default = TlsMode.DISABLE.wire,
                        help = "`verify-full` is the only mode that defends against an " +
                            "attacker on the path.",
                    ),
                ),
            ),
        ),
    )

    override fun validate(descriptor: ConnectionDescriptor): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val target = descriptor.target
        if (target !is ConnectionTarget.Network) {
            issues += ValidationIssue("PostgreSQL connects to a host and a port.")
            return issues
        }
        if (target.host.isBlank()) {
            issues += ValidationIssue("Enter the server's host name or address.", field = "host")
        }
        if (target.port !in 1..65535) {
            issues += ValidationIssue("A port is a number between 1 and 65535.", field = "port")
        }
        if (target.database.isNullOrBlank()) {
            issues += ValidationIssue(
                "PostgreSQL needs a database name; a connection is opened against one database.",
                field = "database",
            )
        }
        if (target.database.orEmpty().length > MAX_DATABASE) {
            issues += ValidationIssue(
                "A database name may be at most $MAX_DATABASE characters.",
                field = "database",
            )
        }
        val user = descriptor.engineOptions[OPTION_USER]
        if (user.isNullOrBlank()) {
            issues += ValidationIssue("Enter the user to connect as.", field = OPTION_USER)
        } else if (user.length > MAX_USER) {
            issues += ValidationIssue(
                "A user name may be at most $MAX_USER characters.",
                field = OPTION_USER,
            )
        }
        return issues
    }

    /**
     * Opens a session, proves it works, and reads the server's version.
     *
     * [policy] decides read-only enforcement, and it is applied where PostgreSQL can
     * be held to it — the pool opens every connection in a `READ ONLY` transaction —
     * rather than by a keyword classifier that a function body compiled last year
     * walks straight past.
     *
     * [drivers] is never consulted: pgjdbc is bundled, so [driverRequirement] is null
     * and there is nothing to resolve.
     */
    override suspend fun connect(
        descriptor: ConnectionDescriptor,
        secrets: SecretBundle,
        policy: SessionPolicy,
        drivers: DriverProvider,
    ): DatabaseSession {
        validate(descriptor).firstOrNull()?.let {
            throw DbException(DbError.UnsupportedConfiguration(it.message))
        }
        val target = descriptor.target as ConnectionTarget.Network
        val config = PostgresConnectionConfig(
            host = target.host,
            port = target.port,
            database = target.database.orEmpty(),
            user = user(descriptor, secrets),
            password = password(secrets),
            tlsMode = tlsMode(descriptor.tls),
            readOnly = policy.readOnly,
        )

        val session = PostgresSession.open(config, policy.statementTimeout)
        return try {
            PostgresEngineSession(session, ServerVersion.parse(session.serverVersion()))
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    /**
     * The user to connect as.
     *
     * From [SecretBundle.UserPassword] where the vault holds one, and from the
     * descriptor's options otherwise, because a user name is not a secret and is
     * stored and displayed in the clear today. Both are read rather than one, so that
     * Phase 4's move of the pair into the vault does not need this call site changed.
     */
    private fun user(descriptor: ConnectionDescriptor, secrets: SecretBundle): String = when (secrets) {
        is SecretBundle.UserPassword -> secrets.user
        else -> descriptor.engineOptions[OPTION_USER].orEmpty()
    }

    private fun password(secrets: SecretBundle): Secret = when (secrets) {
        is SecretBundle.UserPassword -> Secret(secrets.password.copyOf())
        is SecretBundle.Password -> Secret(secrets.password.copyOf())
        SecretBundle.None -> Secret.EMPTY
        else -> throw DbException(
            DbError.UnsupportedConfiguration(
                "PostgreSQL connections authenticate with a user and a password.",
            ),
        )
    }

    private fun tlsMode(tls: TlsConfig): TlsMode = when (tls) {
        TlsConfig.Disabled -> TlsMode.DISABLE
        is TlsConfig.Required -> if (tls.verifyHostname) TlsMode.VERIFY_FULL else TlsMode.REQUIRE
    }
}
