/**
 * Where the catalog vocabulary used to live.
 *
 * The types themselves are in `dev.caracal.engine.api` now, because `CatalogFacet`
 * is declared in terms of them and the SPI cannot depend on `:core`. These aliases
 * exist so that the move cost nothing at the call sites: every `import
 * dev.caracal.core.catalog.SchemaInfo` in the object browser still resolves, to the
 * same class it always did.
 *
 * They are scaffolding for the module split and should be deleted once the last
 * caller has been repointed, which is a Phase 2 job — not a permanent second name
 * for one type.
 */
package dev.caracal.core.catalog

typealias SchemaInfo = dev.caracal.engine.api.SchemaInfo

typealias ObjectKind = dev.caracal.engine.api.ObjectKind

typealias CatalogObject = dev.caracal.engine.api.CatalogObject

typealias ColumnInfo = dev.caracal.engine.api.ColumnInfo

typealias Listing<T> = dev.caracal.engine.api.Listing<T>

typealias CatalogLimits = dev.caracal.engine.api.CatalogLimits
