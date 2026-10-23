package com.thinkmorestupidless.ankka.kotlin.suspend

import com.thinkmorestupidless.ankka.javasdk.spi.HandlerAdapter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * How the Java SDK calls a `suspend fun` handler: on the handler's own virtual thread, inside
 * `runBlocking`, whose event loop is that thread. While the function waits, the thread parks and
 * holds no carrier. The Java SDK loads this class by name ([HandlerAdapter.KOTLIN]); nothing else
 * constructs it.
 */
class SuspendingHandlers : HandlerAdapter {

    override fun call(method: Method, instance: Any, arguments: Array<Any?>): Any? =
        runBlocking {
            val loop = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            withContext(HandlerThread(loop)) { callSuspending(method, instance, arguments) }
        }

    private suspend fun callSuspending(method: Method, instance: Any, arguments: Array<Any?>): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(instance, *arguments, continuation)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }
}
