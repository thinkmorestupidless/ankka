package {{package}};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The whole service against a throwaway database (Docker), called over HTTP as a client would. */
class ServiceTest {

  private static AnkkaTestKit kit;

  @BeforeAll
  static void start() {
    kit = AnkkaTestKit.start(Main.service());
  }

  @AfterAll
  static void stop() {
    kit.close();
  }

  // docs:start service-test
  @Test
  void anItemAddedOverHttpIsReadBackAfterARestart() throws Exception {
    var added =
        send(
            HttpRequest.newBuilder(kit.http().resolve("/carts/c1/items"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"productId\":\"p1\",\"name\":\"Pen\",\"quantity\":2}")));
    assertEquals(204, added.statusCode(), added.body());

    kit.restartService();

    var cart = send(HttpRequest.newBuilder(kit.http().resolve("/carts/c1")).GET());
    assertEquals(200, cart.statusCode(), cart.body());
    assertTrue(cart.body().contains("\"quantity\":2"), cart.body());
  }
  // docs:end service-test

  private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return kit.httpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
