package {{package}}

import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.eventually
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The whole service against a throwaway database (Docker), called over HTTP as a client would. */
class ServiceTest {
    // docs:start service-test
    @Test
    fun `an item added over HTTP is read back after a restart, and reaches the view`() =
        ankkaTestKit(service()) {
            val added =
                send(
                    HttpRequest.newBuilder(http().resolve("/carts/c1/items"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"productId":"p1","name":"Pen","quantity":2}"""))
                )
            assertEquals(204, added.statusCode(), added.body())

            restartService()

            val cart = send(HttpRequest.newBuilder(http().resolve("/carts/c1")).GET())
            assertEquals(200, cart.statusCode(), cart.body())
            assertTrue(cart.body().contains("\"quantity\":2"), cart.body())

            runBlocking {
                eventually {
                    val row = send(HttpRequest.newBuilder(http().resolve("/carts/c1/row")).GET())
                    assertEquals(200, row.statusCode(), row.body())
                    assertTrue(row.body().contains("\"p1\":2"), row.body())
                }
            }
        }
    // docs:end service-test

    private fun AnkkaTestKit.send(request: HttpRequest.Builder): HttpResponse<String> =
        httpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
}
