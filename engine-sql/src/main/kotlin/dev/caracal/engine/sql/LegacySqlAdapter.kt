package dev.caracal.engine.sql

import dev.caracal.core.export.CsvExportReport
import dev.caracal.core.export.CsvOptions
import dev.caracal.core.export.ExportLimits
import dev.caracal.core.result.QueryResult
import dev.caracal.engine.api.DatabaseSession

/**
 * The two calls that still predate [dev.caracal.engine.api.QueryFacet].
 *
 * This seam keeps the structural module split honest without making issue #3 also
 * reconcile the old materialized result with the SPI's streamed outcomes. Issue #4
 * removes it together with `ConnectionRegistry.postgresAdapter`.
 */
interface LegacySqlAdapter {
    suspend fun execute(sql: String): QueryResult

    suspend fun exportCsv(
        sql: String,
        out: Appendable,
        options: CsvOptions = CsvOptions(),
        exportLimits: ExportLimits = ExportLimits(),
    ): CsvExportReport
}

/** A temporary, engine-neutral way for core to reach [LegacySqlAdapter]. */
interface LegacySqlSession : DatabaseSession {
    val legacySqlAdapter: LegacySqlAdapter
}
