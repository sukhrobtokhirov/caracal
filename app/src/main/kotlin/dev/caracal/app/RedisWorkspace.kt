package dev.caracal.app

import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionService
import dev.caracal.core.redis.RedisKey
import kotlinx.coroutines.CoroutineScope

/**
 * The four view models one Redis connection needs, and the two operations the window
 * performs on all of them at once.
 *
 * A holder rather than four parameters threaded through the workspace, for the same
 * reason the panes are one screen: they are four views of one connection, they are
 * pointed at it together, and they are emptied together when the vault locks. Keeping
 * that in one place is what stops a fifth pane being added later and quietly not being
 * cleared.
 */
class RedisWorkspace(service: ConnectionService, scope: CoroutineScope) {

    val browser = RedisBrowserViewModel(service, scope)

    val value = RedisValueViewModel(service, scope)

    val console = RedisConsoleViewModel(service, scope)

    val info = RedisInfoViewModel(service, scope)

    /** Points every pane at [id], or at nothing. */
    fun show(id: ConnectionId?) {
        browser.show(id)
        value.show(id)
        console.show(id)
        info.show(id)
    }

    /** Opens a key in the value pane and marks it as the browser's selection. */
    fun open(key: RedisKey) {
        browser.select(key)
        value.open(key)
    }

    /**
     * Drops everything: the keyspace, the value, the transcript, and the summary.
     *
     * Called when the vault locks, which has just closed every client — so what is on
     * screen describes a server this process can no longer reach, and a console
     * transcript in particular is the one thing here that must not outlive the session
     * that produced it.
     */
    fun clear() {
        browser.clear()
        value.clear()
        console.clear()
        info.clear()
    }
}
