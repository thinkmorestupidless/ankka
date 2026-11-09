package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.http.Caller
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** One answer to a request a test made. */
data class Answer(val status: Int, val body: String, val headers: Map<String, List<String>>) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/** Sends [method] [path] to the service a kit started, as the caller the kit is told, if any. */
fun AnkkaTestKit.send(
    method: String,
    path: String,
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
    caller: Caller? = null,
): Answer {
    val request = HttpRequest.newBuilder(URI.create("${http()}$path"))
    headers.forEach { (name, value) -> request.header(name, value) }
    if (body != null) request.header("Content-Type", "application/json")
    if (caller != null) asCaller(caller).let { request.header(it.key, it.value) }
    request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
    val response = httpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    return Answer(response.statusCode(), response.body(), response.headers().map())
}

fun AnkkaTestKit.get(path: String, headers: Map<String, String> = emptyMap(), caller: Caller? = null): Answer =
    send("GET", path, null, headers, caller)
