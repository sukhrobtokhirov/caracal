package dev.caracal.engine.sqlite

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
import dev.caracal.engine.api.FormKeys
import dev.caracal.engine.api.FormSection
import dev.caracal.engine.api.IntentClassifier
import dev.caracal.engine.api.NamespaceModel
import dev.caracal.engine.api.QuoteStyle
import dev.caracal.engine.api.ReadOnlyEnforcement
import dev.caracal.engine.api.RowIdentitySupport
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.ServerVersion
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.api.TlsConfig
import dev.caracal.engine.api.TransactionSupport
import dev.caracal.engine.api.ValidationIssue

/**
 * SQLite, and the first engine that disagrees with the two the SPI was shaped by.
 *
 * It has no host, no port, no credentials and no schemas; it opens a **file**. That
 * disagreement is why it was chosen as the third engine rather than the easiest one:
 * every generalization Phases 3 and 4 made — [ConnectionTarget] as a sum type,
 * [SecretBundle.None] as a value rather than an absence, a connection form the dialog
 * renders without knowing what is in it — is either real or it is rearrangement, and
 * an engine that exercises none of them cannot tell which.
 *
 * `org.xerial:sqlite-jdbc` is already a dependency of `:core`, where it backs the
 * configuration store. That is not the same thing as engine support, and nothing in
 * `ConfigStore` is reused here: it opens one file it owns, with a schema it wrote,
 * and it has no cursor, no cancellation and no catalog.
 *
 * A class rather than an object for `ServiceLoader`'s sake: a provider on the class
 * path is instantiated through a public no-argument constructor, and a Kotlin `object`
 * has a private one.
 */
class SqliteEngine : DatabaseEngine {

    companion object {
        val ID = EngineId("sqlite")

        /**
         * Whether opening a connection may bring the database into being.
         *
         * On the form because it is a decision only the person typing the path can
         * make, and it is not a safe default in either direction. Off, a typo in a
         * path is an error; on, a typo is a new empty database that looks like the
         * old one with everything missing. Off is the answer that fails loudly.
         */
        const val OPTION_CREATE = "create"

        val CAPABILITIES = EngineCapabilities(
            family = EngineFamily.SQL,
            // The one arm nothing had used. A SQLite connection is the database: there
            // is no schema above a table and no database to select. `ATTACH` adds
            // more, and the catalog lists what is attached, but nothing about a
            // connection asks the user to choose one first.
            namespaceModel = NamespaceModel.NONE,
            // Same as PostgreSQL and for the same reason: every statement runs in a
            // transaction this engine opens and closes, and there is no begin/commit
            // the user drives.
            transactions = TransactionSupport.IMPLICIT,
            // The file is opened `SQLITE_OPEN_READONLY`, which is §5.1's `mode=ro`. The
            // refusal comes out of SQLite rather than out of a keyword scan here, which
            // is what makes it worth the same badge PostgreSQL's gets.
            readOnlyEnforcement = ReadOnlyEnforcement.CONNECTION_URI,
            // `sqlite3_interrupt`, reached through `Statement.cancel()`. Distinct from
            // PostgreSQL's out-of-band request because there is no second connection
            // and no server process — the running statement's own database handle is
            // flagged, and the next step of the byte-code loop returns SQLITE_INTERRUPT.
            cancellation = CancellationSupport.INTERRUPT,
            // `rowid` names a row on every ordinary table, and a `WITHOUT ROWID` table
            // is required to have a primary key. One of the two is always there.
            rowIdentity = RowIdentitySupport.PRIMARY_KEY_OR_PSEUDO,
            // SQLite accepts backticks and brackets too, for the compatibility of it.
            // Double quotes are the standard spelling and the one this engine writes.
            identifierQuote = QuoteStyle.DOUBLE_QUOTE,
            supportsMultipleResultSets = false,
            supportsExplain = true,
            supportsSchemaDiff = false,
            // SQLite has no second channel. There is no `RAISE NOTICE`, and a `PRAGMA`
            // that reports something reports it as rows.
            surfacesNotices = false,
            // The declaration SQLite is the counterexample for. Its five storage
            // classes are integer, float, text, blob and null; `DECIMAL(30,10)` is a
            // declared type over one of those, and a decimal written into it is a
            // float by the time anything reads it back. See [EngineCapabilities].
            exactNumerics = false,
            // SQLite imposes no limit on an identifier's length beyond the limit on
            // the length of the whole statement, which is a megabyte by default.
            maxIdentifierLength = 1_000_000,
            // No port, and `EngineDeclarationTest` holds this to the form: a declared
            // default port with no field to type one into is as wrong as a wrong number.
            defaultPort = null,
        )

        /**
         * The extensions the file picker offers, in the order they are offered.
         *
         * SQLite does not care and neither does this engine: the header inside the
         * file is what says what it is. They exist so a picker can lead with the three
         * names people actually use rather than showing every file on the disk.
         */
        val EXTENSIONS = listOf(".db", ".sqlite", ".sqlite3")
    }

    override val id: EngineId = ID

    override val displayName: String = "SQLite"

    /** Public domain, and already on the classpath. Nothing to download, ever. */
    override val driverRequirement: DriverRequirement? = null

    override val capabilities: EngineCapabilities = CAPABILITIES

    override val intentClassifier: IntentClassifier = SqliteIntent

    /**
     * A path, and one decision about it. No host, no user, no TLS.
     *
     * This is the form that proves the declared-form machinery generalizes: the dialog
     * renders a file field and a checkbox for an engine it has never heard of, and
     * there is no secret section at all — which is also what excuses this engine from
     * the two redaction cases in the conformance suite, visibly, rather than by an
     * empty list of literals nobody would notice was empty.
     */
    override val connectionForm: ConnectionForm = ConnectionForm(
        listOf(
            FormSection(
                title = "Database",
                fields = listOf(
                    FormField.FilePath(
                        key = FormKeys.PATH,
                        label = "Database file",
                        required = true,
                        extensions = EXTENSIONS,
                        // Left to the engine rather than asserted by the form, because
                        // "must exist" is exactly what [OPTION_CREATE] turns off, and a
                        // form field cannot read another field to decide.
                        mustExist = false,
                        help = "The SQLite database to open. A journal or WAL file beside " +
                            "it is opened with it.",
                    ),
                    FormField.Toggle(
                        key = OPTION_CREATE,
                        label = "Create the file if it does not exist",
                        default = false,
                        help = "Off, a path that names nothing is an error. On, it is a new, " +
                            "empty database.",
                    ),
                ),
            ),
        ),
    )

    /**
     * Everything wrong with these settings, and nothing about the state of the disk.
     *
     * The temptation with a file target is to stat the path here and object that it is
     * not there. It is refused deliberately, twice over. A file that exists when a
     * dialog is validated can be gone when the pool asks for it a second later, so the
     * check would be a claim nothing can keep; and this engine's whole point is that a
     * path naming nothing is a *normal* state — it is what creating a database looks
     * like before it is created. So [validate] judges the settings, [connect] judges
     * the file, and only one of them can be right about a moving target.
     */
    override fun validate(descriptor: ConnectionDescriptor): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val target = descriptor.target
        if (target !is ConnectionTarget.File) {
            issues += ValidationIssue("SQLite opens a database file; it does not dial a server.")
            return issues
        }
        if (target.path.toString().isBlank()) {
            issues += ValidationIssue("Choose the database file to open.", field = FormKeys.PATH)
            return issues
        }
        if (descriptor.tls != TlsConfig.Disabled) {
            issues += ValidationIssue(
                "A SQLite database is a file on this machine; there is no connection to encrypt.",
                field = FormKeys.TLS,
            )
        }
        if (descriptor.secretRef != null) {
            issues += ValidationIssue("SQLite does not authenticate; this connection has no credential.")
        }
        return issues
    }

    /**
     * Opens the file, proves it can be read, and hands back a session.
     *
     * [policy] decides the open mode, which is the whole of SQLite's read-only
     * enforcement: `SQLITE_OPEN_READONLY` is applied to the database handle, so a
     * write is refused by SQLite with `SQLITE_READONLY` whether it arrived as an
     * `UPDATE`, inside a trigger, or inside a view somebody defined last year.
     *
     * [secrets] must be [SecretBundle.None]. Anything else is a stored connection that
     * disagrees with this engine's own form about whether it has a credential, and
     * quietly ignoring it would mean a password the user believes is protecting
     * something is protecting nothing.
     *
     * [drivers] is never consulted: the driver is bundled and [driverRequirement] is
     * null.
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
        if (secrets != SecretBundle.None) {
            throw DbException(
                DbError.UnsupportedConfiguration(
                    "A SQLite database is not opened with a credential. Remove the stored secret.",
                ),
            )
        }
        val target = descriptor.target as ConnectionTarget.File
        val config = SqliteConnectionConfig(
            path = target.path,
            readOnly = policy.readOnly,
            // Two spellings of the same decision, because both can reach here: the
            // descriptor's own flag is what a target built in code carries, and the
            // option is what the form writes. Either one asking for it is enough.
            createIfMissing = target.createIfMissing || createIfMissing(descriptor),
        )

        val session = SqliteSession.open(config, policy.statementTimeout)
        return try {
            SqliteEngineSession(session, ServerVersion.parse(session.libraryVersion()))
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    private fun createIfMissing(descriptor: ConnectionDescriptor): Boolean =
        descriptor.target.let { it is ConnectionTarget.File && it.createIfMissing } ||
            descriptor.engineOptions[OPTION_CREATE].toBoolean()
}
