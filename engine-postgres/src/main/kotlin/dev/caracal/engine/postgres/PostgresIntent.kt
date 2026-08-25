package dev.caracal.engine.postgres

import dev.caracal.core.sql.StatementClassifier
import dev.caracal.core.sql.StatementKind
import dev.caracal.engine.api.IntentClassifier
import dev.caracal.engine.api.WriteIntent

/**
 * PostgreSQL's half of §7: what a statement looks like it will do, said by the only
 * code that knows this dialect.
 *
 * A pure translation of [StatementClassifier], which is characterized by Phase 0 and
 * is not touched here. Two of the four arms are worth explaining.
 *
 * [StatementKind.SESSION] becomes [WriteIntent.CONNECTION_AFFECTING] rather than
 * [WriteIntent.WRITE]. `BEGIN`, `SET`, `DISCARD` and `LOCK` succeed and then do
 * nothing: every statement runs in its own transaction that is rolled back, and
 * PostgreSQL's `SET` is transactional, so a `SET search_path` reports success and is
 * gone before the next statement runs. Telling the user that is more use than
 * refusing it, and it is why the classifier keeps them in their own case.
 *
 * [StatementKind.WRITE] becomes [WriteIntent.WRITE] and never [WriteIntent.DDL] or
 * [WriteIntent.DESTRUCTIVE], because the classifier does not distinguish them —
 * `INSERT` and `DROP TABLE` are both just "a modifying keyword appeared". Inventing
 * the distinction here would mean a second keyword table living beside the one Phase
 * 0 pinned down, disagreeing with it the first time either is edited. When something
 * needs `DDL`, the place to add it is `StatementClassifier`, under its tests.
 */
object PostgresIntent : IntentClassifier {

    override fun classify(statement: String): WriteIntent = when (StatementClassifier.classify(statement)) {
        StatementKind.READ -> WriteIntent.READ_ONLY
        StatementKind.SESSION -> WriteIntent.CONNECTION_AFFECTING
        StatementKind.WRITE -> WriteIntent.WRITE
        StatementKind.UNKNOWN -> WriteIntent.UNKNOWN
    }
}
