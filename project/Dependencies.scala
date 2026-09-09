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

    val jsoniter  = "2.40.1"
    val anthropic = "2.61.0"

    val logback        = "1.6.3"
    val munit          = "1.3.6"
    val testcontainers = "1.21.4"
    val r2dbcPostgres  = "1.1.2.RELEASE"
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

  val pekkoActorTestkit       = pekko("actor-testkit-typed")
  val pekkoStreamTestkit      = pekko("stream-testkit")
  val pekkoPersistenceTestkit = pekko("persistence-testkit")

  val pekkoHttp        = "org.apache.pekko" %% "pekko-http"         % V.pekkoHttp
  val pekkoHttpTestkit = "org.apache.pekko" %% "pekko-http-testkit" % V.pekkoHttp

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

  /** Test-only deps every module gets. */
  val commonTest: Seq[ModuleID] = Seq(
    munit             % Test,
    pekkoActorTestkit % Test,
    logback           % Test
  )
}
