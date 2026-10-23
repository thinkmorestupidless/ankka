package com.thinkmorestupidless.ankka.kotlin.suspend

import com.thinkmorestupidless.ankka.javasdk.client.Invocation
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClient
import com.thinkmorestupidless.ankka.javasdk.client.ViewQueries
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery
import java.util.concurrent.Flow.Publisher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.jdk9.asFlow
import kotlinx.coroutines.jdk9.asPublisher as jdk9Publisher

// The component client, the view client and the service client, suspending. A call suspends the
// handler's coroutine; the handler's virtual thread parks and holds no carrier while it waits.

/** [Invocation.invoke], suspending until the component answers. */
suspend fun <I : Any, O : Any> Invocation<I, O>.invokeSuspending(input: I): O = invokeAsync(input).await()

/** [Invocation.invoke] for a handler that takes nothing, suspending until the component answers. */
suspend fun <O : Any> Invocation<Void, O>.invokeSuspending(): O = invokeAsync().await()

/** The row under [key], or `null`, suspending until the view answers. */
suspend fun <Row : Any> ViewQueries<Row>.getSuspending(key: String): Row? = getAsync(key).await().orElse(null)

/** Up to [limit] rows, suspending until the view answers. */
suspend fun <Row : Any> ViewQueries<Row>.allSuspending(limit: Int): List<Row> = allAsync(limit).await()

/** How many rows the view holds, suspending until it answers. */
suspend fun <Row : Any> ViewQueries<Row>.countSuspending(): Long = countAsync().await()

/** A declared query's rows, suspending until the view answers. */
suspend fun <Row : Any> ViewQueries<Row>.askSuspending(
    query: ViewQuery,
    values: Map<String, String> = emptyMap(),
): List<Row> = askAsync(query, values).await()

/** A declared query's rows as a cold [Flow], read as they are collected. */
fun <Row : Any> ViewQueries<Row>.askFlow(query: ViewQuery, values: Map<String, String> = emptyMap()): Flow<Row> =
    askStream(query, values).asFlow()

/** A [Flow] as the publisher a server-sent events route or a stream route returns. */
fun <T : Any> Flow<T>.asPublisher(): Publisher<T> = jdk9Publisher()

// The service client's calls park the handler's own virtual thread, which holds no carrier while it
// waits; the handler's other coroutines run once the answer is in.

/** [ServiceClient.get], read as [R]. */
suspend inline fun <reified R : Any> ServiceClient.getSuspending(path: String): R = get(path, R::class.java)

/** [ServiceClient.post], read as [R]. */
suspend inline fun <B : Any, reified R : Any> ServiceClient.postSuspending(path: String, body: B): R =
    post(path, body, R::class.java)

/** [ServiceClient.put], read as [R]. */
suspend inline fun <B : Any, reified R : Any> ServiceClient.putSuspending(path: String, body: B): R =
    put(path, body, R::class.java)

/** [ServiceClient.delete]. */
@Suppress("RedundantSuspendModifier")
suspend fun ServiceClient.deleteSuspending(path: String) = delete(path)
