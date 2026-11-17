package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.*

/**
 * The pure half of `ankka local up` (feature 066, research R2): what is read, what is refused
 * before anything starts, and the plan each project gives, with no Docker.
 */
class LocalPlanSuite extends munit.FunSuite:

  test("the command's own variables are the platform's, the database's and the broker's") {
    for name <- Vector(
        "ANKKA_DB_NAME",
        "ANKKA_DB_HOST",
        "ANKKA_KAFKA_TOPIC_PREFIX",
        "ANKKA_HTTP_PORT",
        "ANKKA_GRPC_PORT",
        "ANKKA_SECRET_KEY",
        "ANKKA_SERVICE_NAME",
        "ANKKA_OBSERVABILITY_ADDRESS",
        "ANKKA_SIDECAR_ADDRESS",
        "ANKKA_PROCESS_PORT",
        "ANKKA_WASM_MODULE",
        "ANKKA_CLUSTER_MODE",
        "ANKKA_DATABASE",
        "PORT",
        "ANKKA_SERVICES_URL"
      )
    do assert(LocalVariables.isCommandOwned(name), name)
    for name <- Vector("CURRENCY", "ANKKA_AUTH_STAFF_ISSUER", "JAVA_TOOL_OPTIONS", "LOG_LEVEL") do
      assert(!LocalVariables.isCommandOwned(name), name)
  }

  test("a directory that holds no context map is no project") {
    val dir = java.nio.file.Files.createTempDirectory("empty")
    assertEquals(
      LocalProject.read(dir),
      Left(
        Vector(
          s"$dir holds no project: a project's directory holds context-map.json and " +
            "services/<name>/service.json for each service"
        )
      )
    )
  }

  test("a project is read: its map, its descriptors, its topics and its local file") {
    val project =
      LocalProject.read(LocalFixtures.project()).fold(p => fail(p.mkString("; ")), identity)
    assertEquals(project.name, "shop")
    assertEquals(project.services, Vector("orders", "billing"))
    assertEquals(project.descriptor("billing").service.port, 9001)
    assertEquals(project.topics.topics, Vector.empty)
    assertEquals(project.local, LocalFile())
  }

  test("a service the map names with no descriptor is refused, with the map's own words") {
    val root = LocalFixtures.project()
    java.nio.file.Files.delete(root.resolve("services/billing/service.json"))
    val problems = LocalProject.read(root).left.getOrElse(fail("read"))
    assert(
      problems.contains(
        "the context map names \"billing\", and services/billing/service.json does not exist"
      ),
      problems
    )
  }

  test("an invalid descriptor is refused by its path, every problem together") {
    val root = LocalFixtures.project(
      Vector(
        LocalFixtures.Service("orders", 9000, extra = ""","instanceType":"huge""""),
        LocalFixtures.Service("billing", 9001, extra = ""","hosting":"submarine"""")
      )
    )
    val problems = LocalProject.read(root).left.getOrElse(fail("read"))
    assert(problems.exists(_.startsWith("services/orders/service.json: ")), problems)
    assert(problems.exists(_.startsWith("services/billing/service.json: ")), problems)
  }

  private def choices(taken: Set[Int] = Set.empty): LocalState.Choices =
    var next = 40000
    var key  = 0
    LocalState.Choices(
      isFree = p => !taken(p),
      freePort = () => { next += 1; next },
      newKey = () => { key += 1; s"key-$key" }
    )

  test("a port once chosen is never changed, and what is new is chosen") {
    val first = LocalState.completed(
      LocalState(),
      LocalState.Wanted(Vector("orders")),
      choices()
    )
    assertEquals(first.postgresPort, Some(5432))
    assertEquals(first.kafkaPort, Some(9092))
    val second = LocalState.completed(
      first,
      LocalState.Wanted(Vector("orders", "billing")),
      choices(taken = Set(5432, 9092))
    )
    assertEquals(second.postgresPort, first.postgresPort)
    assertEquals(second.observability("orders"), first.observability("orders"))
    assert(second.observability.contains("billing"))
    assertEquals(second.secretKeys("orders"), first.secretKeys("orders"))
  }

  test("a taken 5432 is replaced by a free port, and no two choices are the same") {
    val state = LocalState.completed(
      LocalState(),
      LocalState.Wanted(Vector("orders", "billing"), companionPorts = Map("mail" -> Vector(1025))),
      choices(taken = Set(5432))
    )
    assertNotEquals(state.postgresPort, Some(5432))
    assertEquals(state.ports.size, 5)
  }

  test("each service has a secret key of its own") {
    val state =
      LocalState.completed(LocalState(), LocalState.Wanted(Vector("orders", "billing")), choices())
    assertNotEquals(state.secretKeys("orders"), state.secretKeys("billing"))
    assertEquals(java.util.Base64.getDecoder.decode(LocalState.randomKey()).length, 32)
  }

  test("the state reads back as it was written") {
    val root  = java.nio.file.Files.createTempDirectory("state")
    val state = LocalState.completed(LocalState(), LocalState.Wanted(Vector("orders")), choices())
    LocalState.write(root, state)
    assertEquals(LocalState.read(root), state)
  }

  test("the compose document is written in compose's own words, leaving out what is empty") {
    val compose = Compose(
      "ankka-shop",
      scala.collection.immutable.ListMap(
        "orders" -> Compose.Service(
          image = "orders:1",
          environment = scala.collection.immutable.ListMap("ANKKA_SERVICE_NAME" -> "orders"),
          ports = Vector(Compose.publish("127.0.0.1", 9000, 9000)),
          dependsOn = scala.collection.immutable
            .ListMap("ankka-postgres" -> Compose.Dependency(Compose.Healthy)),
          extraHosts = Vector(Compose.HostGateway),
          memLimit = Some("512m")
        )
      ),
      scala.collection.immutable.ListMap("pgdata" -> Compose.Volume())
    )
    val json = Compose.json(compose)
    for field <- Vector(
        "\"depends_on\"",
        "\"extra_hosts\"",
        "\"mem_limit\"",
        "\"127.0.0.1:9000:9000\""
      )
    do assert(json.contains(field), json)
    for empty <- Vector(
        "\"command\"",
        "\"labels\"",
        "\"network_mode\"",
        "\"healthcheck\"",
        "\"volumes\": ["
      )
    do assert(!json.contains(empty), json)
    assertEquals(
      com.github.plokhotnyuk.jsoniter_scala.core.readFromString[Compose](json),
      compose
    )
  }

  private val machine = LocalPlan.Machine(cliVersion = "0.17.0")

  private def readProject(root: java.nio.file.Path): LocalProject =
    LocalProject.read(root).fold(p => fail(p.mkString("; ")), identity)

  private def stateFor(project: LocalProject): LocalState =
    LocalState.completed(LocalState(), LocalPlan.wanted(project), choices())

  private def planOf(
      project: LocalProject,
      selection: LocalPlan.Selection = LocalPlan.Selection.All,
      m: LocalPlan.Machine = machine
  ): LocalPlan =
    LocalPlan
      .of(project, selection, stateFor(project), m)
      .fold(p => fail(p.mkString("; ")), identity)

  test(
    "each service is told who it is, where its database and broker are, and where the others are"
  ) {
    val project = readProject(
      LocalFixtures.project(
        Vector(
          LocalFixtures.Service("orders", 9000, env = """{"name":"CURRENCY","value":"EUR"}"""),
          LocalFixtures.Service("billing", 9001, extra = ""","grpc":true,"grpcPort":9091""")
        )
      )
    )
    val plan  = planOf(project)
    val state = stateFor(project)
    val env   = plan.environment("orders")
    assertEquals(env("CURRENCY"), "EUR")
    assertEquals(env("ANKKA_SERVICE_NAME"), "orders")
    assertEquals(env("ANKKA_HTTP_PORT"), "9000")
    assertEquals(env("ANKKA_DB_HOST"), "ankka-postgres")
    assertEquals(env("ANKKA_DB_PORT"), "5432")
    assertEquals(env("ANKKA_DB_NAME"), "orders")
    assertEquals(env("ANKKA_DB_USER"), "ankka")
    assertEquals(env("ANKKA_DB_PASSWORD"), "ankka")
    assertEquals(env("ANKKA_KAFKA_BOOTSTRAP_SERVERS"), "ankka-kafka:9094")
    assertEquals(env("ANKKA_KAFKA_TOPIC_PREFIX"), "shop.")
    assertEquals(env("ANKKA_SECRET_KEY"), state.secretKeys("orders"))
    assertEquals(env("ANKKA_OBSERVABILITY_ADDRESS"), "0.0.0.0:7630")
    assertEquals(
      env("JAVA_TOOL_OPTIONS"),
      "-Dankka.local-services.billing=http://host.docker.internal:9001 " +
        "-Dankka.local-grpc-services.billing=host.docker.internal:9091"
    )
    assert(!env.contains("ANKKA_GRPC_PORT"))
    assertEquals(plan.environment("billing")("ANKKA_GRPC_PORT"), "9091")
    val orders = plan.compose.services("orders")
    assertEquals(orders.image, "orders:1")
    assertEquals(
      orders.ports,
      Vector("127.0.0.1:9000:9000", s"127.0.0.1:${state.observability("orders")}:7630")
    )
    assertEquals(orders.extraHosts, Vector("host.docker.internal:host-gateway"))
    assertEquals(orders.memLimit, Some("512m"))
    assertEquals(
      plan.compose.services("billing").ports.take(2),
      Vector("127.0.0.1:9001:9001", "127.0.0.1:9091:9091")
    )
    assertEquals(plan.infrastructure, Vector("ankka-postgres", "ankka-kafka"))
    assertEquals(plan.services, Vector("orders", "billing"))
    assertEquals(
      plan.databases,
      Vector(LocalPlan.Database("orders", None), LocalPlan.Database("billing", None))
    )
    assertEquals(plan.entries.map(_.name), Vector("orders", "billing"))
    assertEquals(
      plan.ready("orders"),
      LocalPlan.ReadyCheck.Observability(
        s"http://127.0.0.1:${state.observability("orders")}/observability/service",
        servesHttp = true
      )
    )
  }

  test("the database server and the broker are as the contract says") {
    val plan     = planOf(readProject(LocalFixtures.project()))
    val postgres = plan.compose.services("ankka-postgres")
    assertEquals(postgres.image, "postgres:17-alpine")
    assertEquals(postgres.ports, Vector("127.0.0.1:5432:5432"))
    assertEquals(postgres.volumes, Vector("pgdata:/var/lib/postgresql/data"))
    assert(postgres.healthcheck.exists(_.test.mkString(" ").contains("pg_isready")))
    val kafka = plan.compose.services("ankka-kafka")
    assertEquals(kafka.image, "apache/kafka:3.8.0")
    assertEquals(kafka.environment("KAFKA_AUTO_CREATE_TOPICS_ENABLE"), "false")
    assertEquals(kafka.environment("KAFKA_HEAP_OPTS"), "-Xmx512m -Xms512m")
    assert(kafka.environment("KAFKA_LISTENERS").contains("HOST://"))
    assert(kafka.environment("KAFKA_ADVERTISED_LISTENERS").contains("HOST://localhost:9092"))
    assert(
      kafka.environment("KAFKA_ADVERTISED_LISTENERS").contains("CONTAINERS://ankka-kafka:9094")
    )
    assertEquals(kafka.ports, Vector("127.0.0.1:9092:9092"))
    assertEquals(plan.compose.volumes.keys.toVector, Vector("pgdata", "kafkadata"))
    assertEquals(plan.compose.name, "ankka-shop")
  }

  test("every container is told where every other service is, whoever is selected") {
    val project = readProject(LocalFixtures.project())
    val plan    = planOf(project, LocalPlan.Selection.Only(Vector("orders")))
    assertEquals(plan.services, Vector("orders"))
    assertEquals(plan.leftOut, Vector("billing"))
    // In the document, so compose does not stop it as an orphan; not brought up.
    assert(plan.compose.services.contains("billing"))
    assert(!plan.databases.exists(_.name == "billing"))
    assert(
      plan
        .environment("orders")("JAVA_TOOL_OPTIONS")
        .contains("-Dankka.local-services.billing=http://host.docker.internal:9001")
    )
    val without = planOf(project, LocalPlan.Selection.Without(Vector("orders")))
    assert(
      without
        .environment("billing")("JAVA_TOOL_OPTIONS")
        .contains("-Dankka.local-services.orders=http://host.docker.internal:9000")
    )
  }

  test("a JAVA_TOOL_OPTIONS the descriptor gives is kept, with the addresses after it") {
    val project = readProject(
      LocalFixtures.project(
        Vector(
          LocalFixtures
            .Service("orders", 9000, env = """{"name":"JAVA_TOOL_OPTIONS","value":"-Xss2m"}"""),
          LocalFixtures.Service("billing", 9001)
        )
      )
    )
    assertEquals(
      planOf(project).environment("orders")("JAVA_TOOL_OPTIONS"),
      "-Xss2m -Dankka.local-services.billing=http://host.docker.internal:9001"
    )
  }

  test("no CPU limit is set, only memory") {
    val json = Compose.json(planOf(readProject(LocalFixtures.project())).compose)
    assert(!json.contains("cpu"), json)
  }

  test("a service with no database is told so and is given none") {
    val project = readProject(
      LocalFixtures.project(
        Vector(
          LocalFixtures.Service("orders", 9000),
          LocalFixtures.Service("billing", 9001, extra = ""","database":"none"""")
        )
      )
    )
    val plan = planOf(project)
    assertEquals(plan.environment("billing")("ANKKA_DATABASE"), "none")
    assert(!plan.environment("billing").contains("ANKKA_DB_HOST"))
    assertEquals(plan.databases.map(_.name), Vector("orders"))
  }

  test("ports are published on the bridge's gateway only when the machine names one") {
    val project = readProject(LocalFixtures.project())
    assert(!planOf(project).compose.services("orders").ports.exists(_.startsWith("172.17.0.1:")))
    val linux = planOf(project, m = machine.copy(gateway = Some("172.17.0.1")))
    assert(linux.compose.services("orders").ports.contains("172.17.0.1:9000:9000"))
    assert(linux.compose.services("orders").ports.contains("127.0.0.1:9000:9000"))
  }

  test("a service of another project the crossings name is a notice, not a failure") {
    val project = readProject(
      LocalFixtures.project(
        crossings = Vector(
          """{"from":"orders","to":"billing","route":"POST /invoices"}""",
          """{"from":"orders","to":"bank/ledger","route":"POST /entries"}"""
        )
      )
    )
    val plan = planOf(project)
    assertEquals(
      plan.notices,
      Vector(
        "the context map names \"bank/ledger\", a service of another project; it is not run here"
      )
    )
  }

  test("a descriptor's own database and broker are not used, and that is said once") {
    val project = readProject(
      LocalFixtures.project(
        Vector(
          LocalFixtures.Service(
            "orders",
            9000,
            env =
              """{"name":"ANKKA_DB_HOST","value":"db.example"},{"name":"ANKKA_KAFKA_BOOTSTRAP_SERVERS","value":"k:1"}"""
          ),
          LocalFixtures.Service("billing", 9001)
        )
      )
    )
    val plan = planOf(project)
    assertEquals(plan.environment("orders")("ANKKA_DB_HOST"), "ankka-postgres")
    assertEquals(plan.environment("orders")("ANKKA_KAFKA_BOOTSTRAP_SERVERS"), "ankka-kafka:9094")
    assertEquals(plan.notices.size, 1)
    assert(
      plan.notices.head.contains("orders ANKKA_DB_HOST, ANKKA_KAFKA_BOOTSTRAP_SERVERS"),
      plan.notices
    )
  }

  test("a descriptor's own database password read from a secret is not used, and needs no value") {
    val project = readProject(
      LocalFixtures.project(
        Vector(
          LocalFixtures.Service(
            "orders",
            9000,
            env =
              """{"name":"ANKKA_DB_PASSWORD","secretKeyRef":{"name":"orders-db","key":"password"}}"""
          ),
          LocalFixtures.Service("billing", 9001)
        )
      )
    )
    val plan = planOf(project)
    assertEquals(plan.environment("orders")("ANKKA_DB_PASSWORD"), "ankka")
    assert(plan.notices.exists(_.contains("orders ANKKA_DB_PASSWORD")), plan.notices)
  }

  test("two services on different runtime versions each ask for their own version's schema") {
    val project = readProject(
      LocalFixtures.project(
        Vector(
          LocalFixtures.Service("orders", 9000, extra = ""","runtime":"0.16.3""""),
          LocalFixtures.Service("billing", 9001, extra = ""","runtime":"0.17.0"""")
        )
      )
    )
    assertEquals(
      planOf(project).databases,
      Vector(
        LocalPlan.Database("orders", Some("0.16.3")),
        LocalPlan.Database("billing", Some("0.17.0"))
      )
    )
  }

  test("two projects on one machine share no compose project, volume, server, topic or entry") {
    val shop = readProject(LocalFixtures.project())
    val bank = readProject(LocalFixtures.project(name = "bank"))
    val a    = planOf(shop)
    val b    = planOf(bank)
    assertNotEquals(a.compose.name, b.compose.name)
    // Compose scopes a volume and the network to its project: `ankka-shop_pgdata`, `ankka-bank_pgdata`.
    assert(a.compose.volumes.values.forall(_.name.isEmpty))
    assert(b.compose.volumes.values.forall(_.name.isEmpty))
    assertEquals(a.environment("orders")("ANKKA_KAFKA_TOPIC_PREFIX"), "shop.")
    assertEquals(b.environment("orders")("ANKKA_KAFKA_TOPIC_PREFIX"), "bank.")
    assertEquals(LocalPlan.composeProject("shop"), "ankka-shop")
    assertNotEquals(a.project, b.project)
  }

  private def refusal(
      project: LocalProject,
      selection: LocalPlan.Selection = LocalPlan.Selection.All,
      m: LocalPlan.Machine = machine
  ): Vector[String] =
    LocalPlan.of(project, selection, stateFor(project), m).left.getOrElse(fail("planned"))

  test("a name on the command line that is not a service is refused") {
    val project = readProject(LocalFixtures.project())
    assertEquals(
      refusal(project, LocalPlan.Selection.Only(Vector("orders", "payments"))),
      Vector("\"payments\" is not a service of shop")
    )
    assertEquals(
      refusal(project, LocalPlan.Selection.Without(Vector("payment"))),
      Vector("\"payment\" is not a service of shop")
    )
  }

  test("a name both given and left out is refused") {
    assertEquals(
      refusal(
        readProject(LocalFixtures.project()),
        LocalPlan.Selection.Only(Vector("orders"), without = Vector("orders"))
      ),
      Vector("\"orders\" is both named and left out")
    )
  }

  test("two services naming one port are refused, for HTTP and gRPC alike") {
    val http = readProject(
      LocalFixtures.project(
        Vector(LocalFixtures.Service("orders", 9000), LocalFixtures.Service("billing", 9000))
      )
    )
    assertEquals(refusal(http), Vector("\"orders\" and \"billing\" both name port 9000"))
    val grpc = readProject(
      LocalFixtures.project(
        Vector(
          LocalFixtures.Service("orders", 9000, extra = ""","grpc":true,"grpcPort":9001"""),
          LocalFixtures.Service("billing", 9001)
        )
      )
    )
    assertEquals(refusal(grpc), Vector("\"orders\" and \"billing\" both name port 9001"))
  }

  test("a port held by something else is refused, and one held by its own container is not") {
    val project = readProject(LocalFixtures.project())
    assertEquals(
      refusal(
        project,
        m = machine.copy(holder = p => Option.when(p == 9001)(LocalPlan.Holder.Other))
      ),
      Vector("\"billing\" names port 9001, which is in use on this machine")
    )
    assertEquals(
      refusal(
        project,
        m =
          machine.copy(holder = p => Option.when(p == 9001)(LocalPlan.Holder.FromSource("billing")))
      ),
      Vector(
        "\"billing\" names port 9001, which is in use on this machine; it is running from its source"
      )
    )
    assert(
      LocalPlan
        .of(
          project,
          LocalPlan.Selection.All,
          stateFor(project),
          machine.copy(holder = p => Option.when(p == 9001)(LocalPlan.Holder.Ours("billing")))
        )
        .isRight
    )
    // A service left out may be held by its source run: that is what leaving it out is for.
    assert(
      LocalPlan
        .of(
          project,
          LocalPlan.Selection.Without(Vector("billing")),
          stateFor(project),
          machine.copy(holder = p => Option.when(p == 9001)(LocalPlan.Holder.FromSource("billing")))
        )
        .isRight
    )
  }

  test("a service named as the database server or the broker is refused") {
    val project = readProject(
      LocalFixtures.project(
        Vector(LocalFixtures.Service("orders", 9000), LocalFixtures.Service("ankka-kafka", 9001)),
        crossings = Vector("""{"from":"orders","to":"ankka-kafka","route":"POST /x"}""")
      )
    )
    assertEquals(
      refusal(project),
      Vector(
        "\"ankka-kafka\" is the name of what the command line runs for every project; name the service something else"
      )
    )
  }

  test("three different problems come back in one answer") {
    val project = readProject(
      LocalFixtures.project(
        Vector(LocalFixtures.Service("orders", 9000), LocalFixtures.Service("billing", 9000))
      )
    )
    val found = refusal(
      project,
      LocalPlan.Selection.Only(Vector("orders", "nobody")),
      machine.copy(holder = p => Option.when(p == 9000)(LocalPlan.Holder.Other))
    )
    assertEquals(found.size, 3, found)
  }

  private def withLocal(root: java.nio.file.Path, json: String): LocalProject =
    LocalFixtures.write(root, "local.json", json)
    readProject(root)

  private val secretOrders = LocalFixtures.Service(
    "orders",
    9000,
    env = """{"name":"PAYMENT_KEY","secretKeyRef":{"name":"payments","key":"key"}}"""
  )

  test("a service is started with the literal environment its descriptor declares") {
    val project = readProject(
      LocalFixtures.project(
        Vector(
          LocalFixtures.Service("orders", 9000, env = """{"name":"CURRENCY","value":"EUR"}"""),
          LocalFixtures.Service("billing", 9001)
        )
      )
    )
    assertEquals(planOf(project).environment("orders")("CURRENCY"), "EUR")
  }

  test(
    "a variable the descriptor reads from a secret takes the value the project's local values give it"
  ) {
    val project = withLocal(
      LocalFixtures.project(Vector(secretOrders, LocalFixtures.Service("billing", 9001))),
      """{"values":{"orders":{"PAYMENT_KEY":"test-key"}}}"""
    )
    assertEquals(planOf(project).environment("orders")("PAYMENT_KEY"), "test-key")
  }

  test(
    "a variable read from a secret with no local value is refused before anything starts, naming the service and the variable"
  ) {
    val project = readProject(
      LocalFixtures.project(Vector(secretOrders, LocalFixtures.Service("billing", 9001)))
    )
    assertEquals(
      refusal(project),
      Vector("\"orders\" reads PAYMENT_KEY from a secret and local.json gives it no value")
    )
  }

  test("a local value replaces a literal the descriptor declares") {
    val project = withLocal(
      LocalFixtures.project(
        Vector(
          LocalFixtures.Service("orders", 9000, env = """{"name":"CURRENCY","value":"EUR"}"""),
          LocalFixtures.Service("billing", 9001)
        )
      ),
      """{"values":{"*":{"CURRENCY":"USD"},"orders":{"CURRENCY":"GBP"}}}"""
    )
    val plan = planOf(project)
    assertEquals(plan.environment("orders")("CURRENCY"), "GBP")
    assertEquals(plan.environment("billing")("CURRENCY"), "USD")
  }

  test("a variable the platform sets is not taken from the descriptor or the local values") {
    val project =
      withLocal(LocalFixtures.project(), """{"values":{"orders":{"ANKKA_DB_NAME":"other"}}}""")
    assertEquals(
      refusal(project),
      Vector("local.json gives \"orders\" ANKKA_DB_NAME, which the command line sets itself")
    )
  }

  test("values for a name that is no service are refused") {
    val project = withLocal(
      LocalFixtures.project(),
      """{"values":{"payments":{"A":"b"}},"run":{"refunds":["sbt","run"]}}"""
    )
    assertEquals(
      refusal(project),
      Vector(
        "local.json gives values to \"payments\", which is not a service of shop",
        "local.json gives values to \"refunds\", which is not a service of shop"
      )
    )
  }

  test(
    "the plan makes each declared topic under the project's prefix, the prefix every service is told"
  ) {
    val root = LocalFixtures.project()
    LocalFixtures.write(
      root,
      "topics.json",
      """{"topics":[{"name":"orders-placed","partitions":6}]}"""
    )
    val plan = planOf(readProject(root))
    assertEquals(plan.topics, Vector(LocalPlan.BrokerTopic("shop.orders-placed", 6, Map.empty)))
    assertEquals(plan.environment("orders")("ANKKA_KAFKA_TOPIC_PREFIX"), "shop.")
    assertEquals(plan.environment("billing")("ANKKA_KAFKA_TOPIC_PREFIX"), "shop.")
  }

  test("a declaration the control plane would refuse is refused before anything starts") {
    val root = LocalFixtures.project()
    LocalFixtures.write(
      root,
      "topics.json",
      """{"topics":[{"name":"audit","partitions":1,"retention":"soon"}]}"""
    )
    val found = refusal(readProject(root))
    assertEquals(found.size, 1, found)
    assert(found.head.startsWith("topic 'audit': retention \"soon\" is not "), found)
  }

  private val Identity =
    """{"name":"identity","image":"quay.io/keycloak/keycloak:26.0","command":["start-dev","--import-realm"],
      |"env":{"KC_DB":"postgres","KC_DB_URL":"{{database.jdbc}}","KC_DB_USERNAME":"{{database.user}}","KC_DB_PASSWORD":"{{database.password}}"},
      |"ports":[{"port":8080,"host":8081}],"files":[{"from":"local/realm.json","to":"/opt/keycloak/data/import/realm.json"}],
      |"ready":{"http":{"port":8080,"path":"/realms/shop"}},"database":true,"neededBy":["orders"]}""".stripMargin

  private def withIdentity(companion: String = Identity, values: String = ""): java.nio.file.Path =
    val root = LocalFixtures.project()
    LocalFixtures.write(root, "local/realm.json", "{}")
    LocalFixtures.write(root, "local.json", s"""{$values"companions":[$companion]}""")
    root

  test("a companion renders as its own container, with its database, its files and its check") {
    val root = withIdentity(
      values = """"values":{"orders":{"ISSUER":"{{companion.identity.url}}/realms/shop"}},"""
    )
    val plan = planOf(readProject(root))
    val c    = plan.compose.services("identity")
    assertEquals(c.image, "quay.io/keycloak/keycloak:26.0")
    assertEquals(c.command, Vector("start-dev", "--import-realm"))
    assertEquals(c.environment("KC_DB_URL"), "jdbc:postgresql://ankka-postgres:5432/identity")
    assertEquals(c.environment("KC_DB_USERNAME"), "ankka")
    assert(c.ports.contains("127.0.0.1:8081:8080"), c.ports)
    assertEquals(
      c.volumes,
      Vector(
        s"${root.resolve("local/realm.json").toAbsolutePath.normalize}:/opt/keycloak/data/import/realm.json:ro"
      )
    )
    assertEquals(c.dependsOn.keySet, Set("ankka-postgres"))
    assert(
      plan.databases.contains(LocalPlan.Database("identity", None, schema = false)),
      plan.databases
    )
    assertEquals(plan.companions, Vector("identity"))
    assertEquals(plan.ready("identity"), LocalPlan.ReadyCheck.Http(8081, "/realms/shop"))
    assertEquals(plan.neededBy, Map("identity" -> Vector("orders")))
    assertEquals(
      plan.environment("orders")("ISSUER"),
      "http://host.docker.internal:8081/realms/shop"
    )
  }

  test("a companion's port left to the command is chosen and kept") {
    val root  = withIdentity(Identity.replace("""{"port":8080,"host":8081}""", """{"port":8080}"""))
    val state = stateFor(readProject(root))
    val port  = state.companionPorts("identity")("8080")
    assertEquals(
      planOf(readProject(root)).ready("identity"),
      LocalPlan.ReadyCheck.Http(port, "/realms/shop")
    )
  }

  test("a companion only services left out need is not started") {
    val plan = planOf(readProject(withIdentity()), LocalPlan.Selection.Without(Vector("orders")))
    assertEquals(plan.companions, Vector.empty)
    assert(plan.compose.services.contains("identity"))
  }

  test("every problem of a companion is refused, together") {
    val bad =
      """{"name":"orders","image":"x","ports":[],"ready":{"tcp":1},"env":{"A":"{{database.url}}"}},
        |{"name":"Mail","image":"x","ports":[{"port":25,"host":9000}],"files":[{"from":"nowhere","to":"/x"}]},
        |{"name":"cache","image":"x","ports":[{"port":6379,"host":30001}],"ready":{"tcp":6379},"neededBy":["payments"]},
        |{"name":"cache","image":"x","ports":[{"port":1}],"ready":{"tcp":1}}""".stripMargin
    val root = LocalFixtures.project()
    LocalFixtures.write(
      root,
      "local.json",
      s"""{"values":{"billing":{"MAIL":"{{companion.smtp}}"}},"companions":[$bad]}"""
    )
    val found = refusal(
      readProject(root),
      m = machine.copy(holder = p => Option.when(p == 30001)(LocalPlan.Holder.Other))
    )
    assertEquals(
      found,
      Vector(
        "the companion \"cache\" is named twice",
        "the companion \"orders\" has the name of a service",
        "the companion \"orders\" declares no port",
        "the companion \"orders\" is asked whether it is ready on port 1, which it does not declare",
        "the companion \"orders\" uses {{database...}} and asks for no database",
        "the companion \"Mail\" is not a valid name",
        "the companion \"Mail\" declares no ready check",
        "the companion \"Mail\" mounts nowhere, which does not exist",
        "the companion \"Mail\" names port 9000, which \"orders\" also names",
        "the companion \"cache\" names port 30001, which is in use on this machine",
        "local.json names \"payments\" as needing the companion \"cache\", which is not a service of shop",
        "local.json names the companion \"smtp\" in a value of \"billing\", and declares no such companion"
      )
    )
  }

  // ── the other hostings (research R10) ─────────────────────────────────────

  private def hosted(extra: String, env: String = ""): LocalProject =
    readProject(
      LocalFixtures.project(
        Vector(
          LocalFixtures.Service("orders", 9000),
          LocalFixtures.Service("billing", 9001, extra = extra, env = env)
        )
      )
    )

  test("a process-hosted service is its sidecar and its process, sharing one network") {
    val project = hosted(
      ""","hosting":"process","protocol":"1.0","runtime":"0.17.0","resources":{"process":{"cpu":"250m","memory":"256Mi"}}""",
      env = """{"name":"ANTHROPIC_API_KEY","value":"k"},{"name":"GREETING","value":"hi"},""" +
        """{"name":"ANKKA_KAFKA_CLIENT_ID","value":"c"}"""
    )
    val plan    = planOf(project)
    val sidecar = plan.compose.services("billing")
    val app     = plan.compose.services("billing-app")
    assertEquals(sidecar.image, "ghcr.io/thinkmorestupidless/ankka-sidecar:0.17.0")
    assertEquals(sidecar.environment.get("ANKKA_PROCESS_ADDRESS"), Some("127.0.0.1:9010"))
    assertEquals(sidecar.environment.get("ANKKA_SIDECAR_PORT"), Some("9011"))
    assertEquals(sidecar.environment.get("ANKKA_DB_NAME"), Some("billing"))
    assertEquals(app.image, "billing:1")
    assertEquals(app.networkMode, Some("service:billing"))
    assertEquals(app.environment.get("ANKKA_PROCESS_PORT"), Some("9010"))
    assertEquals(app.environment.get("ANKKA_SIDECAR_ADDRESS"), Some("127.0.0.1:9011"))
    assertEquals(app.memLimit, Some("256m"))
    assertEquals(app.ports, Vector.empty)
    // The runtime's own reaches the sidecar and not the process, the process's the reverse, the
    // broker's both.
    assertEquals(sidecar.environment.get("ANTHROPIC_API_KEY"), Some("k"))
    assert(!app.environment.contains("ANTHROPIC_API_KEY"), app.environment)
    assertEquals(app.environment.get("GREETING"), Some("hi"))
    assert(!sidecar.environment.contains("GREETING"), sidecar.environment)
    // The local broker's to both; a broker of the descriptor's own is the installation's only.
    val bootstrap = sidecar.environment.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS")
    assert(bootstrap.isDefined, sidecar.environment)
    assertEquals(app.environment.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS"), bootstrap)
    assert(!app.environment.contains("ANKKA_KAFKA_CLIENT_ID"), app.environment)
    assert(!sidecar.environment.contains("ANKKA_KAFKA_CLIENT_ID"), sidecar.environment)
    assert(!app.environment.contains("ANKKA_DB_NAME"), app.environment)
    assertEquals(plan.containersOf(Vector("billing")), Vector("billing", "billing-app"))
  }

  test(
    "a module-hosted service is its module, run once into a volume, and the runtime that loads it"
  ) {
    val project = hosted(""","hosting":"wasm","protocol":"1.0"""")
    val plan    = planOf(project)
    val module  = plan.compose.services("billing-module")
    val runtime = plan.compose.services("billing")
    assertEquals(module.image, "billing:1")
    assertEquals(module.volumes, Vector("billing-module:/ankka/module"))
    assertEquals(runtime.image, "ghcr.io/thinkmorestupidless/ankka-sidecar:0.17.0")
    assertEquals(runtime.volumes, Vector("billing-module:/ankka/module:ro"))
    assertEquals(runtime.environment.get("ANKKA_WASM_MODULE"), Some("/ankka/module/service.wasm"))
    assertEquals(
      runtime.dependsOn.get("billing-module").map(_.condition),
      Some("service_completed_successfully")
    )
    assert(plan.compose.volumes.contains("billing-module"), plan.compose.volumes)
  }

  test(
    "a web-hosted service is the proxy in its local mode and its program in the proxy's network"
  ) {
    val project = hosted(
      ""","hosting":"web","processPort":3000,"mounts":[{"path":"/api","service":"orders"}]""",
      env = """{"name":"TITLE","value":"Shop"}"""
    )
    val plan  = planOf(project)
    val proxy = plan.compose.services("billing")
    val app   = plan.compose.services("billing-app")
    assertEquals(proxy.image, "ghcr.io/thinkmorestupidless/ankka-proxy:0.17.0")
    assertEquals(
      proxy.environment,
      scala.collection.immutable.ListMap(
        "ANKKA_PROXY_LOCAL"          -> "on",
        "ANKKA_PROXY_SERVICE"        -> "billing",
        "ANKKA_PROXY_PORT"           -> "9001",
        "ANKKA_PROXY_PROCESS_PORT"   -> "3000",
        "ANKKA_PROXY_MOUNTS"         -> "/api=orders",
        "ANKKA_PROXY_LOCAL_SERVICES" -> "orders=http://host.docker.internal:9000"
      )
    )
    assertEquals(proxy.ports, Vector("127.0.0.1:9001:9001"))
    assertEquals(proxy.memLimit, Some("192m"))
    assertEquals(app.networkMode, Some("service:billing"))
    assertEquals(app.environment.get("PORT"), Some("3000"))
    assertEquals(app.environment.get("ANKKA_SERVICES_URL"), Some("http://127.0.0.1:7630"))
    assertEquals(app.environment.get("TITLE"), Some("Shop"))
    assertEquals(plan.ready("billing"), LocalPlan.ReadyCheck.Port(9001))
    assertEquals(plan.entries.map(_.name), Vector("orders"))
    assert(!plan.databases.exists(_.name == "billing"), plan.databases)
  }
