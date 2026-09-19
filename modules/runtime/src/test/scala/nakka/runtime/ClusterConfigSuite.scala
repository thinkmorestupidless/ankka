package nakka.runtime

import com.typesafe.config.{ConfigException, ConfigFactory}

import scala.io.Source
import scala.jdk.CollectionConverters.*

/**
 * The layered loader — pure, no actor system.
 *
 * See
 * [contracts/config-layering.md](../../../../../../specs/004-multi-node-clusters/contracts/config-layering.md).
 * Every case passes an explicit environment map; none reads or mutates the JVM's own.
 */
class ClusterConfigSuite extends munit.FunSuite:

  private val none = Map.empty[String, String]

  /** Stands in for a service's own application.conf. */
  private def application(hocon: String) = ConfigFactory.parseString(hocon)

  test("precedence: a service's own application.conf beats the overlay, which beats the base") {
    // The local overlay sets the remoting port to 0; a service that pins it wins.
    val config = ClusterConfig.load(none, application("pekko.remote.artery.canonical.port = 2552"))
    assertEquals(config.getInt("pekko.remote.artery.canonical.port"), 2552)
    // Without the service's say, the overlay's value stands over anything the base might have said.
    assertEquals(ClusterConfig.load(none).getInt("pekko.remote.artery.canonical.port"), 0)
  }

  test("precedence: a system property beats all three") {
    val key = "pekko.remote.artery.canonical.port"
    sys.props(key) = "3333"
    try
      ConfigFactory.invalidateCaches()
      val config = ClusterConfig.load(none, application(s"$key = 2552"))
      assertEquals(config.getInt(key), 3333)
    finally
      sys.props.remove(key): Unit
      ConfigFactory.invalidateCaches()
  }

  test("the base and the overlay are both present at once") {
    val config = ClusterConfig.load(none)
    assertEquals(config.getString("nakka.ask-timeout"), "10s")                      // base only
    assertEquals(config.getString("nakka.cluster.formation"), "join-self-or-seeds") // overlay only
  }

  test("an unknown mode fails at load, naming the modes that exist") {
    val e = intercept[IllegalArgumentException] {
      ClusterConfig.load(Map("NAKKA_CLUSTER_MODE" -> "nope"))
    }
    assert(e.getMessage.contains("nope"), e.getMessage)
    assert(e.getMessage.contains("local"), e.getMessage)
    assert(e.getMessage.contains("kubernetes"), e.getMessage)
  }

  test("kubernetes mode with no POD_IP fails naming POD_IP — never quietly binds loopback") {
    val e = intercept[ConfigException] {
      ClusterConfig.load(
        Map(
          "NAKKA_CLUSTER_MODE"           -> "kubernetes",
          "NAKKA_CLUSTER_SERVICE"        -> "cart",
          "NAKKA_CLUSTER_POD_SELECTOR"   -> "a=b",
          "NAKKA_CLUSTER_CONTACT_POINTS" -> "2"
        )
      )
    }
    assert(e.getMessage.contains("POD_IP"), e.getMessage)
  }

  test("kubernetes mode, fully supplied, resolves the overlay from the environment") {
    val config = ClusterConfig.load(
      Map(
        "NAKKA_CLUSTER_MODE"           -> "kubernetes",
        "POD_IP"                       -> "10.1.2.3",
        "NAKKA_CLUSTER_SERVICE"        -> "cart",
        "NAKKA_CLUSTER_POD_SELECTOR"   -> "app.kubernetes.io/name=cart",
        "NAKKA_CLUSTER_CONTACT_POINTS" -> "2"
      )
    )
    assertEquals(config.getString("nakka.cluster.formation"), "bootstrap")
    assertEquals(config.getBoolean("nakka.join-self-if-no-seed-nodes"), false)
    assertEquals(config.getBoolean("pekko.coordinated-shutdown.exit-jvm"), true)
    assertEquals(config.getString("pekko.remote.artery.canonical.hostname"), "10.1.2.3")
    assertEquals(config.getInt("pekko.remote.artery.canonical.port"), 17355)
    assertEquals(config.getString("pekko.management.http.hostname"), "10.1.2.3")
    assertEquals(config.getInt("pekko.management.http.port"), 7626)
    val bootstrap = config.getConfig("pekko.management.cluster.bootstrap.contact-point-discovery")
    assertEquals(bootstrap.getString("discovery-method"), "kubernetes-api")
    assertEquals(bootstrap.getString("service-name"), "cart")
    assertEquals(bootstrap.getInt("required-contact-point-nr"), 2)
    assertEquals(
      config.getString("pekko.discovery.kubernetes-api.pod-label-selector"),
      "app.kubernetes.io/name=cart"
    )
  }

  test("the base says nothing about how nodes find each other — the rule that makes it a base") {
    // Every jar ships a reference.conf; find nakka's by the header only it carries.
    val ours = getClass.getClassLoader
      .getResources("reference.conf")
      .asScala
      .map(url => Source.fromURL(url).mkString)
      .find(_.contains("Defaults for a nakka service"))
      .getOrElse(fail("could not find nakka's reference.conf on the classpath"))
    for forbidden <- Vector(
        "seed-nodes",
        "canonical.hostname",
        "bootstrap",
        "discovery",
        "join-self"
      )
    do
      assert(
        !ours.contains(forbidden),
        s"reference.conf mentions '$forbidden' — that belongs in an overlay, never the base"
      )
  }

  test("the base names the partition strategy, and does NOT turn exit-jvm on") {
    val config = ClusterConfig.load(none)
    assertEquals(
      config.getString("pekko.cluster.split-brain-resolver.active-strategy"),
      "keep-majority"
    )
    // In the base this would make every stopped actor system exit the JVM — the offline test
    // suite, which stops one per case, died that way the first time it landed there. Only a
    // process that IS a node may exit when its node is downed: the Kubernetes overlay.
    assertEquals(config.getBoolean("pekko.coordinated-shutdown.exit-jvm"), false)
  }

  test("local mode with no environment is today's behaviour: random port, join self") {
    val config = ClusterConfig.load(none)
    assertEquals(config.getString("nakka.cluster.formation"), "join-self-or-seeds")
    assertEquals(config.getBoolean("nakka.join-self-if-no-seed-nodes"), true)
    assertEquals(config.getInt("pekko.remote.artery.canonical.port"), 0)
    assertEquals(config.getString("nakka.cluster.seed-nodes"), "")
  }

  test(
    "local mode: the port and the seed list come from the environment, the seeds as ONE STRING"
  ) {
    val config = ClusterConfig.load(
      Map(
        "NAKKA_CLUSTER_PORT"       -> "17355",
        "NAKKA_CLUSTER_SEED_NODES" -> "pekko://a@h:1,pekko://b@h:2"
      )
    )
    assertEquals(config.getInt("pekko.remote.artery.canonical.port"), 17355)
    // A string, deliberately: an environment variable cannot become a HOCON list, so
    // `pekko.cluster.seed-nodes = ${?ENV}` is a type error. The formation step splits this.
    assertEquals(config.getString("nakka.cluster.seed-nodes"), "pekko://a@h:1,pekko://b@h:2")
  }

  test("only the platform's variables reach the config, not the whole environment") {
    val config = ClusterConfig.load(Map("HOME" -> "/nowhere", "NAKKA_CLUSTER_PORT" -> "1"))
    assert(!config.hasPath("HOME"))
  }

  test(
    "a config a caller built from ConfigFactory.load() alone still gets the overlay beneath it"
  ) {
    // What the test kit does. Without this, the base's silence about peers would fall through to
    // Pekko's own defaults — a fixed remoting port — and two test systems could not share a JVM.
    val raw = ConfigFactory
      .parseString("nakka.ask-timeout = 3s")
      .withFallback(ConfigFactory.load())
      .resolve()
    assert(!raw.hasPath("nakka.cluster.formation"), "premise: the raw config has no overlay")
    val layered = ClusterConfig.layered(raw, none)
    assertEquals(layered.getString("nakka.cluster.formation"), "join-self-or-seeds")
    assertEquals(layered.getInt("pekko.remote.artery.canonical.port"), 0)
    assertEquals(layered.getString("nakka.ask-timeout"), "3s", "the caller's own values survive")
  }

  test("layering a config the loader already produced changes nothing") {
    val once  = ClusterConfig.load(none)
    val twice = ClusterConfig.layered(once, none)
    assertEquals(
      twice.getString("nakka.cluster.formation"),
      once.getString("nakka.cluster.formation")
    )
    assertEquals(
      twice.getInt("pekko.remote.artery.canonical.port"),
      once.getInt("pekko.remote.artery.canonical.port")
    )
  }

  test(
    "the Kubernetes overlay's readiness check names a class that exists, with the shape management wants"
  ) {
    val config = ClusterConfig.load(
      Map(
        "NAKKA_CLUSTER_MODE"           -> "kubernetes",
        "POD_IP"                       -> "10.1.2.3",
        "NAKKA_CLUSTER_SERVICE"        -> "cart",
        "NAKKA_CLUSTER_POD_SELECTOR"   -> "a=b",
        "NAKKA_CLUSTER_CONTACT_POINTS" -> "2"
      )
    )
    val className =
      config.getString("pekko.management.health-checks.readiness-checks.nakka-extensions")
    // A typo here would surface only in a pod, as "/ready" never answering.
    val cls = Class.forName(className)
    assert(
      cls.getConstructors.exists(
        _.getParameterTypes.toSeq == Seq(classOf[org.apache.pekko.actor.ActorSystem])
      ),
      cls.getConstructors.mkString
    )
    assert(
      classOf[() => scala.concurrent.Future[Boolean]].isAssignableFrom(cls) || classOf[Function0[?]]
        .isAssignableFrom(cls)
    )
  }

  test("the kubernetes overlay registers the version route where Pekko Management looks") {
    // `pekko.management.http.routes.<name>`, not `pekko.management.routes`: the wrong key is
    // silently ignored and /nakka/version is a 404 in every pod — which is how it was found.
    val config = ClusterConfig.load(
      env = Map(
        "NAKKA_CLUSTER_MODE"           -> "kubernetes",
        "POD_IP"                       -> "10.0.0.1",
        "NAKKA_CLUSTER_SERVICE"        -> "cart",
        "NAKKA_CLUSTER_POD_SELECTOR"   -> "a=b",
        "NAKKA_CLUSTER_CONTACT_POINTS" -> "2"
      )
    )
    val className = config.getString("pekko.management.http.routes.nakka-version")
    val cls       = Class.forName(className)
    assert(
      classOf[org.apache.pekko.management.scaladsl.ManagementRouteProvider].isAssignableFrom(cls)
    )
    assert(
      cls.getConstructors.exists(
        _.getParameterTypes.toSeq == Seq(classOf[org.apache.pekko.actor.ExtendedActorSystem])
      ),
      cls.getConstructors.mkString
    )
  }
