package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Socket
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Principal
import com.thinkmorestupidless.ankka.javasdk.http.WebSocket
import com.thinkmorestupidless.ankka.kotlin.orNull
import com.thinkmorestupidless.ankka.kotlin.receiveOrNull

/** Admits only a verified token from the suite's test issuer, and says what its principal carried. */
class PrivateEndpoint : HttpEndpoint() {

    override fun acl(): Acl = KotlinReference.authenticated

    private fun who(): Principal = checkNotNull(principal().orNull()) { "no principal" }

    @Get("/private/")
    fun root(): String = "private"

    @Get("/private/me")
    fun me(): String {
        val p = who()
        return """{"subject":${quoted(p.subject)},"roles":${sortedArray(p.roles)},""" +
            """"tier":${quotedOrNull(p.claims["tier"])},"issuer":${quotedOrNull(p.issuer.orNull())}}"""
    }

    // The principal and the caller, sent once when the socket opens.
    @Socket("/private/socket")
    fun socket(socket: WebSocket) {
        val p = who()
        ConformanceEndpoint.socketLog.add("private:${p.subject}")
        socket.send(
            """{"subject":${quoted(p.subject)},"roles":${sortedArray(p.roles)},"caller":${quoted(callerWord(caller()))}}"""
        )
        // Read until the socket closes.
        while (socket.receiveOrNull() != null) {}
    }
}
