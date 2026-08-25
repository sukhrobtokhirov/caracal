package dev.caracal.core.result

import dev.caracal.engine.api.CancelResult
import dev.caracal.engine.api.CellValue
import dev.caracal.engine.api.ColumnDescriptor
import dev.caracal.engine.api.QueryFacet
import dev.caracal.engine.api.ResultDescriptor
import dev.caracal.engine.api.Row
import dev.caracal.engine.api.SplitterConfig
import dev.caracal.engine.api.StatementExecution
import dev.caracal.engine.api.StatementOutcome
import dev.caracal.engine.api.StatementRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * An engine, as far as the shared machinery is concerned.
 *
 * Everything `:engine-sql` gained with issue #4 — assembling a result, writing an
 * export — reads [StatementOutcome]s and nothing else, so it can be held to its
 * contract without a driver, a container, or a server. That is the point of the
 * conversion living here rather than in `:core`: the half that is not PostgreSQL's
 * has tests that are not PostgreSQL's either.
 */
class FakeQueryFacet(
    private val outcomes: (StatementRequest) -> Flow<StatementOutcome>,
) : QueryFacet {
    /** The request the last execution was given, for a case that asserts on it. */
    var lastRequest: StatementRequest? = null
        private set

    override val splitterConfig = SplitterConfig()

    override suspend fun execute(request: StatementRequest): StatementExecution {
        lastRequest = request
        return object : StatementExecution {
            override val outcomes: Flow<StatementOutcome> = outcomes(request)
            override suspend fun cancel(): CancelResult = CancelResult.Unsupported("a fake runs nothing")
        }
    }
}

/** A statement execution over a fixed list of outcomes. */
fun execution(vararg outcomes: StatementOutcome): StatementExecution = object : StatementExecution {
    override val outcomes: Flow<StatementOutcome> = flow { outcomes.forEach { emit(it) } }
    override suspend fun cancel(): CancelResult = CancelResult.Unsupported("a fake runs nothing")
}

/** A `Rows` outcome over [rows], described by one column per value of the first. */
fun rows(vararg rows: List<CellValue>, columns: List<ColumnDescriptor>? = null): StatementOutcome.Rows =
    StatementOutcome.Rows(
        descriptor = ResultDescriptor(
            columns = columns ?: List(rows.firstOrNull()?.size ?: 0) { ColumnDescriptor("c$it", "text") },
        ),
        rows = flow { rows.forEach { emit(Row(it)) } },
    )
