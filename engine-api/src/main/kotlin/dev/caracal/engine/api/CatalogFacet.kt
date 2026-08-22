package dev.caracal.engine.api

/**
 * The object browser's view of a server.
 *
 * Lazy by contract, not by convention: a schema with fifty thousand tables is
 * unusual and entirely possible, and the tree would try to lay out every one of
 * them. [Listing] carries the bound it was cut at.
 *
 * What is deliberately *not* shared is what fills these in. §12 of the spec is
 * explicit that `pg_catalog` and `information_schema` are not the same shape, and
 * that pretending otherwise produces a lowest-common-denominator browser. The
 * queries stay in the engine. Only their answers are common.
 */
interface CatalogFacet {
    /**
     * The schemas, or the databases, or whatever this engine's
     * [EngineCapabilities.namespaceModel] says sits above a table.
     */
    suspend fun schemas(includeSystem: Boolean = false): Listing<SchemaInfo>

    suspend fun objects(schema: String, kind: ObjectKind): Listing<CatalogObject>

    suspend fun columns(schema: String, relation: String): List<ColumnInfo>
}
