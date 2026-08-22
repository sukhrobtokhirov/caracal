package dev.caracal.core.engines

import dev.caracal.core.connections.Engine
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.EngineCapabilities
import dev.caracal.engine.api.EngineId
import dev.caracal.engine.postgres.PostgresEngine
import dev.caracal.engine.redis.RedisEngine

/**
 * The engines this build has, and the one place that knows which they are.
 *
 * It is a `when` today and it is meant to be. Phase 3 replaces the body of this
 * object with a `ServiceLoader<DatabaseEngine>` and nothing outside it changes,
 * which is the whole reason it exists as an object rather than as three `when`s
 * spread across the registry, the descriptor mapper, and the settings window.
 *
 * The important consequence is upward: **the UI reads capabilities from here and
 * never switches on an engine.** A `when (connection.engine)` in a view is not a
 * shortcut, it is a missing capability — the fix is to add one to
 * [EngineCapabilities] rather than an arm to the view. What the UI is still allowed
 * to key on an engine is artwork: a logo cannot be declared in `:engine-api`,
 * because putting a drawing in the module every other module depends on would drag
 * a UI toolkit in behind it. That lookup lives in the UI with a fallback, so an
 * engine this build has no artwork for still gets a mark rather than a blank.
 */
object Engines {

    /** Every engine on the classpath, in the order they are offered. */
    val all: List<DatabaseEngine> = listOf(PostgresEngine, RedisEngine)

    /** The implementation behind a stored connection's engine. */
    fun of(engine: Engine): DatabaseEngine = when (engine) {
        Engine.POSTGRES -> PostgresEngine
        Engine.REDIS -> RedisEngine
    }

    /** The implementation with this identifier, or null for one this build does not have. */
    fun byId(id: EngineId): DatabaseEngine? = all.firstOrNull { it.id == id }
}

/** What this engine can do, in the form the UI is allowed to ask. */
val Engine.capabilities: EngineCapabilities get() = Engines.of(this).capabilities

/** Shown to people. `PostgreSQL`, not `postgres`. */
val Engine.displayName: String get() = Engines.of(this).displayName

/** This engine's identifier in the SPI's vocabulary. */
val Engine.id: EngineId get() = Engines.of(this).id
