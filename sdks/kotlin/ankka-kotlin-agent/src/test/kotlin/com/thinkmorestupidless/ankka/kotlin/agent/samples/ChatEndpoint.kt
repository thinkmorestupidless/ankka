package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.javasdk.annotations.Sse
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent
import com.thinkmorestupidless.ankka.kotlin.agent.forAgent
import com.thinkmorestupidless.ankka.kotlin.agent.streamFlow
import com.thinkmorestupidless.ankka.kotlin.agent.streamRef
import com.thinkmorestupidless.ankka.kotlin.suspend.asPublisher
import java.util.concurrent.Flow
import kotlinx.coroutines.flow.map

/** Serves the weather agent's streamed reply as server-sent events. */
class ChatEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    // docs:start sse
    @Sse("/chat/{session}")
    fun chat(session: String): Flow.Publisher<SseEvent> =
        componentClient()
            .forAgent(session)
            .streamFlow(streamRef(WeatherAgent::chat), "What is the weather?")
            .map { SseEvent.text(it) }
            .asPublisher()
    // docs:end sse
}
