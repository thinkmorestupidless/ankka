package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.Callers
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent
import java.util.stream.Stream

// An ACL belongs to an endpoint class, so each route whose ACL differs is a class of its own.

/** Says who called: admits the internet and the service `orders`. */
class CallersEndpoint : HttpEndpoint() {

    override fun acl(): Acl = Acl.allowCallers(Callers.internet(), Callers.service("orders"))

    @Get("/callers/whoami")
    fun whoami(): String = callerWord(caller())
}

/** The `/callers` routes only the service itself may call. */
class SelfCallersEndpoint : HttpEndpoint() {

    override fun acl(): Acl = Acl.allowCallers(Callers.self())

    @Get("/callers/self")
    fun self(): String = "self"

    @Sse("/callers/events")
    fun events(): Stream<SseEvent> = Stream.of(SseEvent.text("tick"))
}

/** Whoever the project granted this route to. */
class GrantedCallersEndpoint : HttpEndpoint() {

    override fun acl(): Acl = Acl.allowCallers(Callers.granted())

    @Get("/callers/granted")
    fun granted(): String = "granted"
}

/** A route whose ACL differs from the rest of `/conformance`: it admits nobody. */
class ClosedEndpoint : HttpEndpoint() {

    override fun acl(): Acl = Acl.denyAll()

    @Get("/conformance/closed")
    fun closed(): String = "never reached"
}
