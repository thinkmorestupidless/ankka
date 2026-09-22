import sbt.*

/** Single source of truth for every external version in the build. */
object Dependencies {

  object V {
    val scala = "3.9.0"

    val pekko          = "1.7.0"
    val pekkoHttp      = "1.4.0"
    val pekkoR2dbc     = "1.2.0"
    val pekkoProj      = "1.1.0"
    val pekkoProjR2dbc = "1.2.0"
    val pekkoKafka     = "1.2.0"

    /**
     * Pekko Management's own line. 1.2.1 is the last 1.x and was verified against Pekko 1.7.0
     * during feature 004's planning; the 2.0.0 milestones pull a different Pekko. Bump these
     * together.
     */
    val pekkoMgmt = "1.2.1"

    val jsoniter  = "2.40.1"
    val anthropic = "2.61.0"

    val logback        = "1.6.3"
    val munit          = "1.3.6"
    val testcontainers = "1.21.4"
    val r2dbcPostgres  = "1.1.2.RELEASE"
    val fabric8        = "7.9.0"
    val decline        = "2.6.2"
    val nimbusJoseJwt  = "10.9.1"

    /**
     * Must match the jackson-databind that fabric8 resolves — currently 2.21.x.
     *
     * The Scala module refuses to load against a databind outside its own minor range, so a
     * mismatch is a runtime failure in serialisation rather than a build error. Bumping fabric8
     * will break this; `AnkkaServiceCodecSuite` fails immediately and loudly when it does, which is
     * the intended way to find out.
     */
    val jackson = "2.21.4"
  }

  // ── Pekko ────────────────────────────────────────────────────────────────
  private def pekko(m: String) = "org.apache.pekko" %% s"pekko-$m" % V.pekko

  val pekkoActorTyped           = pekko("actor-typed")
  val pekkoStream               = pekko("stream")
  val pekkoStreamTyped          = pekko("stream-typed")
  val pekkoSlf4j                = pekko("slf4j")
  val pekkoClusterTyped         = pekko("cluster-typed")
  val pekkoClusterShardingTyped = pekko("cluster-sharding-typed")
  val pekkoPersistenceTyped     = pekko("persistence-typed")
  val pekkoPersistenceQuery     = pekko("persistence-query")
  val pekkoSerializationJackson = pekko("serialization-jackson")
  val pekkoDiscovery            = pekko("discovery")

  // ── Pekko Management: how a node finds its peers in Kubernetes (feature 004) ──
  // In the runtime rather than a module of their own, so the same build runs locally and deployed;
  // a local run carries them and never starts them.
  private def pekkoMgmt(m: String) = "org.apache.pekko" %% s"pekko-$m" % V.pekkoMgmt

  val pekkoManagement             = pekkoMgmt("management")
  val pekkoManagementClusterHttp  = pekkoMgmt("management-cluster-http")
  val pekkoManagementBootstrap    = pekkoMgmt("management-cluster-bootstrap")
  val pekkoDiscoveryKubernetesApi = pekkoMgmt("discovery-kubernetes-api")

  val pekkoActorTestkit       = pekko("actor-testkit-typed")
  val pekkoStreamTestkit      = pekko("stream-testkit")
  val pekkoPersistenceTestkit = pekko("persistence-testkit")

  val pekkoHttp        = "org.apache.pekko" %% "pekko-http"         % V.pekkoHttp
  val pekkoHttpTestkit = "org.apache.pekko" %% "pekko-http-testkit" % V.pekkoHttp

  /**
   * Pinned everywhere, not just where ankka uses pekko-http. pekko-management 1.2.1 declares
   * pekko-http 1.1.0 and pekko-http-spray-json 1.1.0; eviction lifts the former to 1.4.0 wherever
   * `http` is on the classpath but nothing lifts the latter, and Pekko HTTP checks at startup that
   * every artifact in its family is the same version — a mixed set fails the first ActorSystem.
   */
  val pekkoHttpFamily: Seq[ModuleID] = Seq(
    "pekko-http",
    "pekko-http-core",
    "pekko-parsing",
    "pekko-http-spray-json",
    "pekko-http-testkit"
  ).map(m => "org.apache.pekko" %% m % V.pekkoHttp)

  val pekkoR2dbc = "org.apache.pekko" %% "pekko-persistence-r2dbc" % V.pekkoR2dbc
  // The plugin declares the driver as test-scope only, so it must be added explicitly.
  val r2dbcPostgres     = "org.postgresql"    % "r2dbc-postgresql"              % V.r2dbcPostgres
  val pekkoProjection   = "org.apache.pekko" %% "pekko-projection-core"         % V.pekkoProj
  val pekkoProjectionEs = "org.apache.pekko" %% "pekko-projection-eventsourced" % V.pekkoProj
  val pekkoProjectionR2dbc = "org.apache.pekko" %% "pekko-projection-r2dbc" % V.pekkoProjR2dbc
  val pekkoKafka           = "org.apache.pekko" %% "pekko-connectors-kafka" % V.pekkoKafka

  // ── Serialization ────────────────────────────────────────────────────────
  val jsoniterCore = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % V.jsoniter
  val jsoniterMacros =
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter

  // ── Model providers ──────────────────────────────────────────────────────
  val anthropicJava = "com.anthropic" % "anthropic-java" % V.anthropic

  // ── Misc ─────────────────────────────────────────────────────────────────
  val logback             = "ch.qos.logback"     % "logback-classic" % V.logback
  val munit               = "org.scalameta"     %% "munit"           % V.munit
  val testcontainersPg    = "org.testcontainers" % "postgresql"      % V.testcontainers
  val testcontainersKafka = "org.testcontainers" % "kafka"           % V.testcontainers
  val testcontainersK3s   = "org.testcontainers" % "k3s"             % V.testcontainers

  // ── Control plane, operator ──────────────────────────────────────────────
  val fabric8 = "io.fabric8"    % "kubernetes-client" % V.fabric8
  val decline = "com.monovore" %% "decline"           % V.decline

  /** JOSE/JWT verification for the control plane. Deliberately not in any published module. */
  val nimbusJoseJwt = "com.nimbusds" % "nimbus-jose-jwt" % V.nimbusJoseJwt

  /**
   * Scala support for fabric8's serialisation.
   *
   * Without it `Option` encodes as `{"empty":false,"defined":true}` and Scala collections do not
   * round-trip at all — both silent, and both only visible once a resource reaches a real API
   * server. `AnkkaServiceCodecSuite` is the guard.
   */
  val jacksonScala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % V.jackson

  /** Test-only deps every module gets. */
  val commonTest: Seq[ModuleID] = Seq(
    munit             % Test,
    pekkoActorTestkit % Test,
    logback           % Test
  )
}
