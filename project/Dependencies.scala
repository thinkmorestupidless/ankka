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

    val logback = "1.6.3"
    val munit   = "1.3.6"
    // GherkinSuite: Cucumber's own parser and step-expression matcher, not Cucumber-JVM's runner.
    val gherkin             = "42.0.1"
    val cucumberExpressions = "20.1.0"
    val testcontainers      = "1.21.4"
    val r2dbcPostgres       = "1.1.2.RELEASE"
    val fabric8             = "7.9.0"
    val decline             = "2.6.2"
    val nimbusJoseJwt       = "10.9.1"

    /**
     * Reads a view's declared statements, so the runtime can refuse one that writes or reads
     * another table before the database is sent it (feature 031, R7). Dual-licensed Apache 2.0 or
     * LGPL 2.1; in `runtime` only.
     */
    val jsqlparser = "5.4"

    /** Test scope only: mints certificates in-process for the TLS suites (feature 014, R13). */
    val bouncyCastle = "1.86"

    /**
     * The WebAssembly runtime the sidecar hosts a module with (`sidecar/wasm`): pure JVM, no native
     * code. In `sidecar` only, never `runtime`, so no published library carries it.
     */
    val chicory = "1.7.5"

    /**
     * grpc-java, named here rather than taken from the ScalaPB plugin's `grpcJavaVersion`. The
     * plugin declares the line it was built against (1.62.2 for 0.11.20), and the build then served
     * the sidecar protocol on whatever that was — 1.46.0 until feature 020, a 2022 release from
     * before the HTTP/2 rapid-reset fixes. That is tolerable on a pod's loopback and not for
     * `ankka-grpc`, which listens on the network; reflection v1 also needs 1.66 or later. ScalaPB's
     * generated code uses only descriptor builders, `ServerCalls`/`ClientCalls` and the proto
     * descriptor suppliers, and grpc-java 1.84 is still on protobuf-java 3.25, ScalaPB 0.11's line;
     * the sidecar suites and the Python and TypeScript SDKs' conformance runs, on the rebuilt
     * image, are the proof. Every grpc-java artifact the build uses is declared at this version, so
     * eviction never mixes two.
     */
    val grpc = "1.84.0"

    /**
     * Must match the jackson-databind that fabric8 resolves — currently 2.21.x.
     *
     * The Scala module refuses to load against a databind outside its own minor range, so a
     * mismatch is a runtime failure in serialisation rather than a build error. Bumping fabric8
     * will break this; `AnkkaServiceCodecSuite` fails immediately and loudly when it does, which is
     * the intended way to find out.
     */
    val jackson = "2.21.4"

    /**
     * The OpenTelemetry SDK's OTLP exporters, in `ankka-telemetry-otlp` only: `runtime` records
     * spans with no library at all, and this module hands them to the SDK's exporter (feature 026,
     * R2). Its OTLP exporter defaults to an OkHttp sender, which is excluded for the JDK's own HTTP
     * client: OkHttp is not on the classpath of `runtime` or the control plane, and a sender is one
     * more thing an image would carry for nothing.
     */
    val openTelemetry = "1.66.0"
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
  val logback             = "ch.qos.logback"     % "logback-classic"      % V.logback
  val munit               = "org.scalameta"     %% "munit"                % V.munit
  val gherkin             = "io.cucumber"        % "gherkin"              % V.gherkin
  val cucumberExpressions = "io.cucumber"        % "cucumber-expressions" % V.cucumberExpressions
  val testcontainersPg    = "org.testcontainers" % "postgresql"           % V.testcontainers
  val testcontainersKafka = "org.testcontainers" % "kafka"                % V.testcontainers
  val testcontainersK3s   = "org.testcontainers" % "k3s"                  % V.testcontainers

  // ── Control plane, operator ──────────────────────────────────────────────
  val fabric8 = "io.fabric8"    % "kubernetes-client" % V.fabric8
  val decline = "com.monovore" %% "decline"           % V.decline

  // ── gRPC: the sidecar protocol (feature 009) and gRPC endpoints (feature 020) ──
  // grpc-java with ScalaPB, not pekko-grpc: pekko-grpc runs on pekko-http and every artifact it
  // pulled would need adding to the family pin above. ScalaPB's runtime comes from its compiler
  // plugin, so the generated code and its runtime can never disagree; grpc-java's version is
  // `V.grpc`, for the reason given there. `grpc-netty-shaded` so an image carries no second Netty
  // beside Pekko's.
  val scalapbRuntime: ModuleID =
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion
  val scalapbRuntimeGrpc: ModuleID =
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
  val grpcNettyShaded: ModuleID = "io.grpc" % "grpc-netty-shaded" % V.grpc
  val grpcStub: ModuleID        = "io.grpc" % "grpc-stub"         % V.grpc
  val grpcProtobuf: ModuleID    = "io.grpc" % "grpc-protobuf"     % V.grpc

  // ── Telemetry export (feature 026) ───────────────────────────────────────
  val otelExporterOtlp: ModuleID =
    ("io.opentelemetry" % "opentelemetry-exporter-otlp" % V.openTelemetry)
      .exclude("io.opentelemetry", "opentelemetry-exporter-sender-okhttp")
  val otelSenderJdk: ModuleID =
    "io.opentelemetry" % "opentelemetry-exporter-sender-jdk" % V.openTelemetry

  /** The reflection services, v1 and v1alpha, for a service that opts in (feature 020). */
  val grpcServices: ModuleID = "io.grpc" % "grpc-services" % V.grpc

  /** JOSE/JWT verification; a dependency of `ankka-auth-oidc` and of nothing else (feature 022). */
  val nimbusJoseJwt = "com.nimbusds" % "nimbus-jose-jwt" % V.nimbusJoseJwt

  /** A view's declared statements are parsed with it; a direct dependency of `ankka-runtime`. */
  val jsqlparser = "com.github.jsqlparser" % "jsqlparser" % V.jsqlparser

  val bcpkix = "org.bouncycastle" % "bcpkix-jdk18on" % V.bouncyCastle

  /** The WebAssembly runtime and its runtime compiler to JVM bytecode, for the spike only. */
  val chicoryRuntime  = "com.dylibso.chicory" % "runtime"  % V.chicory
  val chicoryCompiler = "com.dylibso.chicory" % "compiler" % V.chicory
  val chicoryWabt     = "com.dylibso.chicory" % "wabt"     % V.chicory

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
