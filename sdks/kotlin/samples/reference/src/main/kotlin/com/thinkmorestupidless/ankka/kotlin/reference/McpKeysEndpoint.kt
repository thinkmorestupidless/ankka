package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Body
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Post
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeyRequest
import com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeySummary
import com.thinkmorestupidless.ankka.javasdk.mcp.Expiry
import com.thinkmorestupidless.ankka.javasdk.mcp.IssuedClientKey
import java.util.Optional

/** The reference's own routes over its client keys, as a product would write them. */
class McpKeysEndpoint : HttpEndpoint() {

    /** What a post is asked to issue: a key that never expires, for the cases. */
    @Manifest("mcp-key-request")
    data class McpKeyRequest(val subject: String, val scope: Set<String>?, val attributes: Map<String, String>?)

    override fun acl(): Acl = Acl.allowAll()

    @Post("/mcp-keys")
    fun issue(@Body body: McpKeyRequest): IssuedClientKey =
        clientKeys().issue(
            ClientKeyRequest(
                body.subject,
                Expiry.never(),
                Optional.empty(),
                body.scope ?: emptySet(),
                body.attributes ?: emptyMap(),
                Optional.empty(),
            )
        )

    @Get("/mcp-keys")
    fun list(): List<ClientKeySummary> = clientKeys().list()

    @Delete("/mcp-keys/{id}")
    fun revoke(id: String): String {
        clientKeys().revoke(id)
        return "revoked"
    }
}
