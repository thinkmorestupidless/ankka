package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Post
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent
import com.thinkmorestupidless.ankka.kotlin.suspend.askFlow
import com.thinkmorestupidless.ankka.kotlin.suspend.asPublisher
import java.util.concurrent.Flow
import kotlinx.coroutines.flow.map

/** Places nodes of a tree, asks what is under one, and streams and watches the rows. */
class TreeEndpoint : HttpEndpoint() {

    override fun acl(): Acl = Acl.allowAll()

    private fun node(id: String): EntityRef = componentClient().forEventSourcedEntity(id)

    @Post("/tree/{nodeId}")
    fun place(nodeId: String): String = node(nodeId).call(TreeNode::place).invoke("")

    @Post("/tree/{nodeId}/under/{parentId}")
    fun placeUnder(nodeId: String, parentId: String): String = node(nodeId).call(TreeNode::place).invoke(parentId)

    // docs:start declared-recursive-query
    @Get("/tree/{nodeId}/below")
    fun below(nodeId: String): List<String> =
        viewClient().forView(TreeRows::class.java).ask(TreeRows.UNDER, mapOf("row" to nodeId)).map { it.key }
    // docs:end declared-recursive-query

    // docs:start stream
    // Every row's key, as the database yields the rows: one event each, then the end.
    @Sse("/tree/streamed")
    fun streamed(): Flow.Publisher<SseEvent> =
        viewClient().forView(TreeRows::class.java).askFlow(TreeRows.ALL_ROWS).map { SseEvent.text(it.key) }.asPublisher()
    // docs:end stream

    // docs:start watch
    // The rows now, `caught-up`, then each row as it is written, as named events.
    @Sse("/tree/watched")
    fun watched(): Flow.Publisher<SseEvent> = viewClient().forView(TreeRows::class.java).watch(TreeRows.ALL_ROWS, emptyMap()).asSse()
    // docs:end watch
}
