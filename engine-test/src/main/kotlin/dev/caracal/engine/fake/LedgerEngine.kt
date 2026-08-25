package dev.caracal.engine.fake

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
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.api.TransactionSupport
import dev.caracal.engine.api.ValidationIssue
import dev.caracal.engine.api.WriteIntent

/**
 * An engine that dials nothing, and exists to prove that one can be added.
 *
 * Phase 3's acceptance criterion is that putting a [DatabaseEngine] on the classpath
 * makes it appear in the application with no change to the application. That is not
 * a claim reading the code can settle — the failure it is about is a list somewhere
 * that nobody remembered — so this is registered on the test classpath and the tests
 * assert that the connection dialog, the settings window and the store all handle it.
 *
 * It is deliberately unlike both bundled engines in every way the declaration can
 * express:
 *
 * - it opens a **file**, so it declares no host and no port, and its connections have
 *   a [ConnectionTarget.File] rather than a network target;
 * - it declares **no password**, so a dialog that assumes every engine has one draws
 *   a field this engine never asked for;
 * - it declares a **toggle** and a **choice that is not TLS**, which are the two field
 *   kinds neither bundled engine uses;
 * - it has an **objection of its own** — the file extension — so the tests can prove
 *   that a message from an engine reaches the field it is about.
 *
 * [connect] throws. Nothing here is a database, and a fake session would be a second
 * thing to keep true.
 */
class LedgerEngine : DatabaseEngine {

    companion object {
        val ID = EngineId("ledger")

        /** The only extension this engine will open. Its own rule, and its own message. */
        const val EXTENSION = ".ledger"

        const val OPTION_MODE = "mode"

        const val OPTION_JOURNAL = "journal"
    }

    override val id: EngineId = ID

    override val displayName: String = "Ledger"

    override val capabilities: EngineCapabilities = EngineCapabilities(
        family = EngineFamily.SQL,
        namespaceModel = NamespaceModel.NONE,
        transactions = TransactionSupport.IMPLICIT,
        readOnlyEnforcement = ReadOnlyEnforcement.CONNECTION_URI,
        cancellation = CancellationSupport.INTERRUPT,
        rowIdentity = RowIdentitySupport.PRIMARY_KEY,
        identifierQuote = QuoteStyle.DOUBLE_QUOTE,
        supportsMultipleResultSets = false,
        supportsExplain = false,
        supportsSchemaDiff = false,
        surfacesNotices = false,
        exactNumerics = false,
        maxIdentifierLength = 64,
        // A file has no port, and a UI that assumes one has to cope with that.
        defaultPort = null,
    )

    override val driverRequirement: DriverRequirement? = null

    override val intentClassifier: IntentClassifier = IntentClassifier { WriteIntent.UNKNOWN }

    override val connectionForm: ConnectionForm = ConnectionForm(
        listOf(
            FormSection(
                title = "File",
                fields = listOf(
                    FormField.FilePath(
                        key = FormKeys.PATH,
                        label = "Ledger file",
                        required = true,
                        // False so a test can describe a file without creating one.
                        mustExist = false,
                        extensions = listOf(EXTENSION),
                        help = "The file this connection opens. Nothing else is read.",
                    ),
                ),
            ),
            FormSection(
                title = "Options",
                fields = listOf(
                    FormField.Choice(
                        key = OPTION_MODE,
                        label = "Mode",
                        options = listOf("ro" to "Read only", "rw" to "Read and write"),
                        default = "ro",
                    ),
                    FormField.Toggle(key = OPTION_JOURNAL, label = "Keep a journal", default = true),
                ),
            ),
        ),
    )

    override fun validate(descriptor: ConnectionDescriptor): List<ValidationIssue> {
        val target = descriptor.target
        if (target !is ConnectionTarget.File) {
            return listOf(ValidationIssue("A ledger connection opens a file."))
        }
        if (!target.path.toString().endsWith(EXTENSION)) {
            return listOf(
                ValidationIssue("A ledger file is named $EXTENSION.", field = FormKeys.PATH),
            )
        }
        return emptyList()
    }

    override suspend fun connect(
        descriptor: ConnectionDescriptor,
        secrets: SecretBundle,
        policy: SessionPolicy,
        drivers: DriverProvider,
    ): DatabaseSession = throw UnsupportedOperationException("The ledger engine dials nothing.")
}
