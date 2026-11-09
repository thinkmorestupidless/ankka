package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The photos endpoint against the kit's store in memory. */
class PhotosTest {
    private fun photos() = Ankka.service("photos").register(Listing::class.java).withEndpoint(PhotosEndpoint::class.java)

    @Test
    fun `the kit's store is the service's, so what one keeps the other reads`() =
        ankkaTestKit(photos()) {
            // docs:start kit-storage
            storage().defaultBucket().putBytes("a/b.png", "png".toByteArray(), "image/png")
            val described = get("/storage/stat?name=a/b.png")
            assertEquals(200, described.status, described.body)
            assertContains(described.body, "\"contentType\":\"image/png\"")
            // docs:end kit-storage
            assertEquals(204, send("POST", "/storage/objects", """{"name":"c.txt","contentType":"text/plain","text":"hello"}""").status)
            val download = get("/storage/download?name=c.txt")
            assertEquals("hello", download.body)
            assertContains(download.header("Content-Type") ?: "", "text/plain")
            assertEquals("hello", String(storage().defaultBucket().getBytes("c.txt").orElseThrow()))
            assertContains(get("/storage/list").body, "\"name\":\"c.txt\"")
            assertEquals(204, send("DELETE", "/storage/objects?name=c.txt").status)
            assertEquals(404, get("/storage/stat?name=c.txt").status)
        }

    @Test
    fun `an upload is passed to the bucket as it arrives, and a photo's name reaches the listing`() =
        ankkaTestKit(photos()) {
            val bytes = ByteArray(300_000) { 'b'.code.toByte() }
            val upload =
                HttpRequest.newBuilder(URI.create("${http()}/storage/upload?name=big.bin"))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build()
            val answer = httpClient().send(upload, HttpResponse.BodyHandlers.ofString())
            assertEquals(204, answer.statusCode(), answer.body())
            assertEquals(bytes.size.toLong(), storage().defaultBucket().stat("big.bin").orElseThrow().length())

            val recorded = send("POST", "/storage/listings/l1/photos", """{"name":"p.png","contentType":"image/png","text":"png"}""")
            assertEquals(204, recorded.status, recorded.body)
            assertEquals(listOf("p.png"), componentClient().forEventSourcedEntity("l1").call(Listing::photos).invoke().photos)
        }
}
