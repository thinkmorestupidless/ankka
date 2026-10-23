package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Body
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Post
import com.thinkmorestupidless.ankka.javasdk.annotations.Socket
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse
import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef
import com.thinkmorestupidless.ankka.javasdk.client.ServiceCallFailed
import com.thinkmorestupidless.ankka.javasdk.client.ServiceIdentityMismatch
import com.thinkmorestupidless.ankka.javasdk.client.ServiceUnanswered
import com.thinkmorestupidless.ankka.javasdk.client.ServiceUnresolvable
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent
import com.thinkmorestupidless.ankka.javasdk.http.WebSocket
import com.thinkmorestupidless.ankka.javasdk.storage.Bucket
import com.thinkmorestupidless.ankka.javasdk.storage.SignedAccess
import com.thinkmorestupidless.ankka.kotlin.getOrNull
import com.thinkmorestupidless.ankka.kotlin.headerOrNull
import com.thinkmorestupidless.ankka.kotlin.orNull
import com.thinkmorestupidless.ankka.kotlin.queryOrNull
import com.thinkmorestupidless.ankka.kotlin.receiveOrNull
import com.thinkmorestupidless.ankka.kotlin.statOrNull
import com.thinkmorestupidless.ankka.kotlin.workflowFailureOrNull
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.stream.Stream

/** The protocol's edge cases as routes, the Scala reference's `/conformance` route for route. */
class ConformanceEndpoint : HttpEndpoint() {

    @Manifest("echo")
    data class Echo(val a: List<String>, val b: String?, val headers: Map<String, String>)

    /** What a call to another service came to, the same in every language. */
    @Manifest("service-call-record")
    data class ServiceCallRecord(
        val outcome: String,
        val status: Int = 0,
        val contentType: String = "",
        val body: String = "",
        val answer: String = "",
        val message: String = "",
    )

    /** What a storage call did, the same in every language. */
    @Manifest("storage-record")
    data class StorageRecord(
        val outcome: String,
        val code: String = "",
        val message: String = "",
        val contentType: String = "",
        val text: String = "",
        val length: Long = 0,
        val names: List<String> = emptyList(),
        val url: String = "",
    )

    override fun acl(): Acl = Acl.allowAll()

    private fun entity(id: String): EntityRef = componentClient().forEventSourcedEntity(id)

    private fun profile(id: String): EntityRef = componentClient().forKeyValueEntity(id)

    /** What a sidecar reported through its errors; nothing in process. */
    @Get("/conformance/problems")
    fun problems(): List<String> = emptyList()

    // A call to another service, as the case asks for it: `service`, `method` and `path` from the
    // query, the body and every `X-Conformance-*` header sent on, and two headers no handler may
    // send, added to show they never arrive.
    @Post("/conformance/service-call")
    fun serviceCall(@Body body: String): ServiceCallRecord {
        val service = request().requiredQuery("service")
        val method = request().requiredQuery("method")
        val path = request().requiredQuery("path")
        val headers = buildMap {
            for ((name, value) in request().headers()) if (name.lowercase().startsWith("x-conformance-")) put(name, value)
            put("X-Ankka-Caller", "ankka://elsewhere/impostor")
            put("Host", "elsewhere")
        }
        val client = services().of(service)
        return try {
            if (request().queryOrNull("mode") == "typed") {
                ServiceCallRecord("response", 200, body = client.getText(path, headers))
            } else {
                val bytes = if (body.isEmpty()) null else body.toByteArray()
                val contentType = if (body.isEmpty()) null else request().headerOrNull("Content-Type")
                val answer = client.request(method, path, bytes, contentType, headers)
                val named = answer.headers.firstOrNull { it.key.equals("x-answer", ignoreCase = true) }?.value ?: ""
                ServiceCallRecord("response", answer.status, answer.contentType, answer.text(), named)
            }
        } catch (e: ServiceCallFailed) {
            ServiceCallRecord("failed", e.status(), body = e.body())
        } catch (e: ServiceUnresolvable) {
            ServiceCallRecord("unresolvable", message = e.message.orEmpty())
        } catch (e: ServiceIdentityMismatch) {
            ServiceCallRecord("mismatch", message = e.message.orEmpty())
        } catch (e: ServiceUnanswered) {
            ServiceCallRecord("unanswered", message = e.message.orEmpty())
        } catch (e: CommandException) {
            ServiceCallRecord("refused", message = e.problem().detail)
        }
    }

    // The secret store. The name is a query parameter because it may hold a slash.
    @Post("/conformance/secrets")
    fun putSecret(@Body value: String): Done {
        secrets().put(request().requiredQuery("name"), value)
        return Done.done()
    }

    @Get("/conformance/secrets")
    fun getSecret(): String {
        val name = request().requiredQuery("name")
        return secrets().getOrNull(name) ?: throw ProblemException(Problem.notFound("no secret '$name'"))
    }

    @Delete("/conformance/secrets")
    fun deleteSecret(): Done {
        secrets().delete(request().requiredQuery("name"))
        return Done.done()
    }

    // The service's buckets. `bucket` names one (absent: the unnamed bucket) and `name` the object;
    // `op` is get, stat, list (under `prefix`), url or signed-url (`access` read or keep). A post
    // keeps its body under `name`; a post with `rules` set to `name` or `prefix` instead tries its
    // body as an object's name or a listing's prefix, and keeps nothing.
    @Post("/conformance/storage")
    fun keep(@Body body: String): StorageRecord = storageCall { bucket ->
        when (request().queryOrNull("rules")) {
            null ->
                bucket.putBytes(
                    request().requiredQuery("name"),
                    body.toByteArray(),
                    request().headerOrNull("Content-Type") ?: "application/octet-stream",
                )
            "prefix" -> bucket.list(body)
            else -> bucket.stat(body)
        }
        StorageRecord("ok")
    }

    @Get("/conformance/storage")
    fun read(): StorageRecord = storageCall { bucket ->
        val name = request().queryOrNull("name") ?: ""
        when (val op = request().requiredQuery("op")) {
            "get" ->
                bucket.getBytes(name).orNull()?.let { bytes ->
                    val type = bucket.statOrNull(name)?.contentType ?: ""
                    StorageRecord("ok", contentType = type, text = String(bytes), length = bytes.size.toLong())
                } ?: StorageRecord("absent")
            "stat" -> bucket.statOrNull(name)?.let { StorageRecord("ok", contentType = it.contentType, length = it.length) } ?: StorageRecord("absent")
            "list" -> StorageRecord("ok", names = bucket.list(request().queryOrNull("prefix") ?: "").map { it.name })
            "url" -> StorageRecord("ok", url = bucket.url(name).toString())
            "signed-url" -> {
                val access = if (request().queryOrNull("access") == "keep") SignedAccess.KEEP else SignedAccess.READ
                StorageRecord("ok", url = bucket.signedUrl(name, access).toString())
            }
            else -> StorageRecord("refused", "BAD_REQUEST", "no storage op '$op'")
        }
    }

    @Delete("/conformance/storage")
    fun remove(): StorageRecord = storageCall { bucket ->
        bucket.delete(request().requiredQuery("name"))
        StorageRecord("ok")
    }

    /** The bucket the request names, and every refusal as a record rather than a status. */
    private fun storageCall(f: (Bucket) -> StorageRecord): StorageRecord =
        try {
            val named = request().queryOrNull("bucket")
            f(if (named != null) storage().bucket(named) else storage().defaultBucket())
        } catch (e: CommandException) {
            StorageRecord("refused", codeWord(e.problem()), e.problem().detail)
        }

    @Get("/conformance/echo")
    fun echo(): Echo =
        Echo(
            request().queryAll("a"),
            request().queryOrNull("b"),
            mapOf("x-one" to (request().headerOrNull("x-one") ?: ""), "x-two" to (request().headerOrNull("x-two") ?: "")),
        )

    @Get("/conformance/status/{code}")
    fun status(code: Int): String = throw ProblemException(Problem(code, "", "status $code as asked"))

    @Get("/conformance/boom")
    fun boom(): String = throw RuntimeException("boom from the handler")

    @Sse("/conformance/stream/{session}")
    @Suppress("UNUSED_PARAMETER") // the path names a session; the stream is the same for every one
    fun stream(session: String): Stream<SseEvent> = listOf(" leading space", "two\nlines", "plain").map { SseEvent.text(it) }.stream()

    // Echoes each frame; "context" is answered with the room and the opening request's `tag`.
    @Socket("/conformance/socket/{room}")
    fun socket(room: String, socket: WebSocket) {
        generateSequence { socket.receiveOrNull() }.forEach { frame ->
            socket.send(if (frame == "context") "$room ${request().queryOrNull("tag") ?: ""}" else frame)
        }
        socketLog.add("closed:$room")
    }

    @Socket("/conformance/socket-once")
    fun socketOnce(socket: WebSocket) {
        socket.receive()
    }

    @Socket("/conformance/socket-fail")
    fun socketFail(socket: WebSocket) {
        socket.receive()
        throw RuntimeException("the socket handler broke")
    }

    @Get("/conformance/socket-log")
    fun socketLog(): List<String> = socketLog.toList()

    @Post("/conformance/profile/{id}")
    fun setProfile(id: String, @Body name: String): String = profile(id).call(Profile::set).invoke(name)

    @Get("/conformance/profile/{id}")
    fun getProfile(id: String): String = profile(id).call(Profile::get).invoke()

    @Delete("/conformance/profile/{id}")
    fun deleteProfile(id: String): String = profile(id).call(Profile::delete).invoke()

    @Post("/conformance/checkout/{id}")
    fun startCheckout(id: String, @Body mode: String): String = componentClient().forWorkflow(id).call(Checkout::start).invoke(mode)

    @Get("/conformance/checkout/{id}")
    fun checkoutStatus(id: String): String = componentClient().forWorkflow(id).call(Checkout::status).invoke()

    @Get("/conformance/checkout/{id}/row")
    fun checkoutRow(id: String): CheckoutRows.CheckoutRow =
        viewClient().forView(CheckoutRows::class.java).getOrNull(id) ?: throw ProblemException(Problem.notFound("no row for '$id'"))

    @Get("/conformance/checkout-rows/{standing}")
    fun checkoutRows(standing: String): List<String> =
        viewClient().forView(CheckoutRows::class.java).ask(CheckoutRows.BY_STANDING, mapOf("standing" to standing)).map { it.id }

    @Get("/conformance/checkout/{id}/end")
    fun checkoutEnd(id: String): String {
        val end = profile("end-$id").call(Profile::get).invoke()
        if (end == "none") throw ProblemException(Problem.notFound("'$id' has not ended"))
        return end
    }

    // Starts a checkout and answers with how it ended, as one request; a failed one is 424.
    @Post("/conformance/checkout-await/{id}")
    fun checkoutAwait(id: String, @Body mode: String): String =
        try {
            componentClient().forWorkflow(id).callThenAwaitEnd(Checkout::start, Duration.ofSeconds(30)).invoke(mode).status
        } catch (failed: CommandException) {
            val end = failed.workflowFailureOrNull ?: throw failed
            throw ProblemException(Problem(424, "", "${end.step.orNull() ?: ""}: ${end.reason}"))
        }

    @Post("/conformance/remind/{id}")
    fun remind(id: String): Done {
        timers().schedule("remind-$id", Duration.ofSeconds(1), Reminder::remind, id)
        return Done.done()
    }

    // A recurring timer: due at once, then every second.
    @Post("/conformance/recur/{id}")
    fun recur(id: String): Done {
        timers().scheduleRecurring("recur-$id", Duration.ZERO, Duration.ofSeconds(1), Reminder::tick, id)
        return Done.done()
    }

    // The same timer set again, with a delay a replacement would be first due after.
    @Post("/conformance/recur/{id}/again")
    fun recurAgain(id: String): Done {
        timers().scheduleRecurring("recur-$id", Duration.ofSeconds(60), Duration.ofSeconds(1), Reminder::tick, id)
        return Done.done()
    }

    @Post("/conformance/recur/{id}/cancel")
    fun recurCancel(id: String): Done {
        timers().cancel("recur-$id")
        return Done.done()
    }

    @Post("/conformance/recur-refused/{id}")
    fun recurRefused(id: String): Done {
        try {
            timers().scheduleRecurring("recur-$id", Duration.ZERO, Duration.ZERO, Reminder::tick, id)
        } catch (e: IllegalArgumentException) {
            throw ProblemException(Problem.badRequest(e.message.orEmpty()))
        }
        return Done.done()
    }

    @Get("/conformance/{id}/count")
    fun count(id: String): Int = entity(id).call(ConformanceEntity::count).invoke()

    // A handler that answers nothing: the call is never awaited, and the route says so with 204.
    @Post("/conformance/{id}/no-reply")
    fun noReply(id: String): Done {
        entity(id).call(ConformanceEntity::noReply).invokeAsync()
        return Done.done()
    }

    // The Scala reference forwards any handler by its wire name; this one names each the suite asks.
    @Post("/conformance/{id}/record")
    fun record(id: String, @Body input: String): String = entity(id).call(ConformanceEntity::record).invoke(input)

    @Post("/conformance/{id}/record-many")
    fun recordMany(id: String, @Body n: String): String = entity(id).call(ConformanceEntity::recordMany).invoke(n.trim().toInt())

    @Post("/conformance/{id}/refuse")
    fun refuse(id: String): String = entity(id).call(ConformanceEntity::refuse).invoke()

    @Post("/conformance/{id}/delete")
    fun delete(id: String): String = entity(id).call(ConformanceEntity::delete).invoke()

    @Post("/conformance/{id}/expire")
    fun expire(id: String, @Body millis: String): String = entity(id).call(ConformanceEntity::expire).invoke(millis.trim().toLong())

    // A handler that throws is answered by nobody: the runtime stops the entity and the call runs
    // out of time. The Scala reference's forwarder awaits its call for ten seconds and answers an
    // unanswered one as an unhandled failure, a 500; this route answers the same way.
    @Post("/conformance/{id}/misbehave")
    fun misbehave(id: String): String =
        try {
            entity(id).call(ConformanceEntity::misbehave).withTimeout(Duration.ofSeconds(10)).invoke()
        } catch (e: CommandException) {
            if (e.problem().status != 504) throw e
            throw IllegalStateException("misbehave was not answered", e)
        }

    companion object {
        /**
         * What the socket routes' handlers noticed: `closed:<room>` when a room's socket closed, and
         * `private:<subject>` when the authenticated one ran.
         */
        val socketLog = ConcurrentLinkedQueue<String>()
    }
}

/** A refusal's code as the protocol says it: `BAD_REQUEST`. */
private fun codeWord(problem: Problem): String =
    when (problem.status) {
        400 -> "BAD_REQUEST"
        401 -> "UNAUTHORIZED"
        403 -> "FORBIDDEN"
        404 -> "NOT_FOUND"
        409 -> "CONFLICT"
        503 -> "UNAVAILABLE"
        504 -> "TIMEOUT"
        else -> "INTERNAL"
    }
