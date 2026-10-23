package com.thinkmorestupidless.ankka.kotlin.suspend

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * The thread of the handler that is running: a `suspend fun` handler starts on it, and returns to
 * it with `withContext(AnkkaDispatcher)` after work done elsewhere.
 *
 * The request context, the trace and the call's origin are thread-locals of the handler's thread,
 * so a component client called from another dispatcher does not carry them. Call the platform from
 * the handler's own thread.
 */
object AnkkaDispatcher : CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val handler =
            context[HandlerThread]
                ?: throw IllegalStateException(
                    "AnkkaDispatcher is the thread of a running handler; this coroutine is not in one"
                )
        handler.loop.dispatch(context, block)
    }

    override fun toString(): String = "AnkkaDispatcher"
}

/** The event loop on the handler's virtual thread, carried in the handler's coroutine context. */
internal class HandlerThread(val loop: CoroutineDispatcher) : AbstractCoroutineContextElement(HandlerThread) {
    companion object Key : CoroutineContext.Key<HandlerThread>
}
