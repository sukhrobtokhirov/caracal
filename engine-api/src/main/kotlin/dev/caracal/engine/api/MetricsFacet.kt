package dev.caracal.engine.api

/**
 * What a server says about itself, for a dashboard.
 *
 * Never fails for want of permission, and that is the contract rather than an
 * implementation note: a restricted ACL that permits browsing and forbids `INFO` is
 * an ordinary way to hand someone read access to a production cache, and the key
 * tools have to keep working when the dashboard cannot be drawn. So a refusal
 * returns a [ServerInfo] marked [ServerInfo.restricted], which is a banner, and a
 * thrown failure is reserved for a connection that has actually broken.
 *
 * [ServerInfo] is Redis-shaped today because Redis is the only engine that
 * implements this, and the spec's own catalogue lists PostgreSQL and MySQL as later
 * providers. The record widens when one of them arrives with fields it needs;
 * widening it now, against no implementation, would be guessing at which of
 * `pg_stat_*`'s several hundred columns matter — and the answer to that is decided
 * by a dashboard nobody has designed.
 */
interface MetricsFacet {

    suspend fun info(): ServerInfo
}
