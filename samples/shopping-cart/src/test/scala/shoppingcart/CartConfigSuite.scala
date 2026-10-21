package shoppingcart

import com.thinkmorestupidless.ankka.runtime.ClusterConfig
import com.typesafe.config.ConfigFactory

/**
 * The cart's own configuration changes the platform's only where its descriptor says: a call's
 * timeout is the platform's ten seconds unless `CART_ASK_TIMEOUT` is set.
 */
class CartConfigSuite extends munit.FunSuite:

  test("a cart whose descriptor sets no ask timeout waits the platform's ten seconds") {
    assume(!sys.env.contains("CART_ASK_TIMEOUT"), "CART_ASK_TIMEOUT is set in this environment")
    assertEquals(ClusterConfig.load(Map.empty).getString("ankka.ask-timeout"), "10s")
  }

  test("a cart whose descriptor sets an ask timeout waits that long") {
    // The file's own line, resolved against a variable this test can give: the environment's is
    // the JVM's, which no test can set.
    val line = ConfigFactory.parseResources("application.conf")
    val set = line
      .withFallback(ConfigFactory.parseString("CART_ASK_TIMEOUT = 14m"))
      .withFallback(ConfigFactory.defaultReference())
      .resolve()
    assertEquals(set.getString("ankka.ask-timeout"), "14m")
  }

  test("a cart whose descriptor sets a request timeout holds a request open that long") {
    val line = ConfigFactory.parseResources("application.conf")
    val set = line
      .withFallback(ConfigFactory.parseString("CART_REQUEST_TIMEOUT = 15m"))
      .withFallback(ConfigFactory.defaultReference())
      .resolve()
    assertEquals(set.getString("pekko.http.server.request-timeout"), "15m")
    assume(!sys.env.contains("CART_REQUEST_TIMEOUT"), "CART_REQUEST_TIMEOUT is set here")
    assertEquals(
      ClusterConfig.load(Map.empty).getString("pekko.http.server.request-timeout"),
      "20 s"
    )
  }

  test("a cart whose descriptor sets an idle timeout keeps a waiting connection open that long") {
    val line = ConfigFactory.parseResources("application.conf")
    val set = line
      .withFallback(ConfigFactory.parseString("CART_IDLE_TIMEOUT = 16m"))
      .withFallback(ConfigFactory.defaultReference())
      .resolve()
    assertEquals(set.getString("pekko.http.server.idle-timeout"), "16m")
    assume(!sys.env.contains("CART_IDLE_TIMEOUT"), "CART_IDLE_TIMEOUT is set here")
    assertEquals(ClusterConfig.load(Map.empty).getString("pekko.http.server.idle-timeout"), "60 s")
  }
