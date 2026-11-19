package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.Settings
import com.thinkmorestupidless.ankka.kotlin.dsl.routes
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** A greeting by name, described for the service's OpenAPI document; a name nobody has is refused. */
class GreetingsEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        described({ summary("A greeting for one name"); answers<String>(); refuses(404, "nobody has that name") }) {
            get("/greetings/{name}") {
                val name = path("name")
                if (name == "nobody") throw ProblemException(Problem.notFound("nobody"))
                "hello, $name"
            }
        }
    }
}

// docs:start served
fun greetings(): ServiceBuilder =
    Ankka.service("greetings")
        .withEndpoint(GreetingsEndpoint::class.java)
        // GET /_ankka/openapi.json, answered to the callers this ACL admits.
        .withOpenApi(Acl.allowAll())
// docs:end served

private fun AnkkaTestKit.get(path: String): HttpResponse<String> =
    httpClient().send(HttpRequest.newBuilder(http().resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

/** The served document's body. */
fun servedDocument(): String = ankkaTestKit(greetings()) { get("/_ankka/openapi.json").body() }

// docs:start held
/** Two calls of a test, each answer checked against the service's document. */
fun heldToItsDocument() {
    // Closing fails, listing every answer the document does not allow.
    ankkaTestKit(greetings(), Settings.defaults().heldToDocument()) {
        get("/greetings/ada")
        get("/greetings/nobody") // 404, which the route's description names
    }
}
// docs:end held
