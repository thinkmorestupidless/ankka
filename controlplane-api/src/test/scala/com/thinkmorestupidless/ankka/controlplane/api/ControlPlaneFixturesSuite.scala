package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, LocalDate}
import scala.jdk.CollectionConverters.*

/**
 * Writes `console/package/fixtures/control-plane/<Type>.json` and `<Type>.minimal.json` from the
 * wire codecs in `Wire`, and fails when a regenerated file differs from the committed one.
 *
 * The console's client decodes these responses with schemas of its own, in TypeScript. The fixtures
 * are how the two are held together: the package's tests decode every file, the full sample with
 * every optional field set and the minimal one with none, so a field renamed, added or dropped here
 * fails there — and this suite fails first, naming the type, when the codec's output changes at
 * all. `-Dankka.docs.update=true` rewrites the files, as it rewrites the generated reference pages.
 *
 * A `Wire` codec with no sample fails the last test: the list of types is read from `Wire`'s own
 * source, so adding a codec there without adding a fixture here cannot pass.
 */
class ControlPlaneFixturesSuite extends munit.FunSuite:

  private val dir: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("console/package")))
      .map(_.resolve("console/package/fixtures/control-plane"))
      .getOrElse(fail("could not find console/package from " + Paths.get("").toAbsolutePath))

  private val update = sys.props.get("ankka.docs.update").contains("true")

  private val at    = Instant.parse("2026-09-28T10:00:00Z")
  private val later = Instant.parse("2026-12-27T10:00:00Z")

  private def fixture[A](name: String, full: A, minimal: A)(using
      c: JsonValueCodec[A]
  ): Vector[(String, String)] =
    def wrap(value: A) = s"""{"type":"$name","json":${writeToString(value)}}""" + "\n"
    Vector(s"$name.json" -> wrap(full), s"$name.minimal.json" -> wrap(minimal))

  private val registry = RegistrySummary("ghcr.io", "robot", Some(at), Some("Olive Owner"))
  private val quota    = Quota(Some(3), Some(10), Some(20))
  private val usage    = Usage(1, 2, 3)

  /** Every wire type, by the name the console's schemas use, with a full and a minimal sample. */
  private val fixtures: Vector[(String, String)] = Vector(
    fixture(
      "AuthDiscovery",
      AuthDiscovery("https://auth.example.com/realms/ankka", "ankka-cli", "ankka-controlplane"),
      AuthDiscovery("i", "c", "a")
    ),
    fixture(
      "Whoami",
      Whoami(
        "sub-1",
        Some("Olive Owner"),
        Some("owner@example.com"),
        emailVerified = true,
        platformAdmin = true,
        Vector(OrganizationMembership("acme", "Acme", Role.Owner))
      ),
      Whoami("sub-1")
    ),
    fixture(
      "Installation",
      Installation(
        "0.12.0",
        Some(CloudInstallation("gcp", "acme-production", "europe-west2", Some("keys/ankka")))
      ),
      Installation("0.12.0")
    ),
    fixture(
      "OrganizationSummary",
      OrganizationSummary(
        "acme",
        "Acme",
        2,
        disabled = true,
        Some(Role.Member),
        Some(quota),
        usage
      ),
      OrganizationSummary("acme", "Acme", 0)
    ),
    fixture(
      "OrganizationDetail",
      OrganizationDetail("acme", "Acme", disabled = true, Some(quota), usage),
      OrganizationDetail("acme", "Acme")
    ),
    fixture("Quota", quota, Quota()),
    fixture(
      "ProjectSummary",
      ProjectSummary("shop", "Shop", "acme", 4, Some(registry)),
      ProjectSummary("shop", "Shop", "acme", 0)
    ),
    fixture(
      "ProjectDetail",
      ProjectDetail("shop", "Shop", "acme", Some(registry)),
      ProjectDetail("shop", "Shop", "acme")
    ),
    fixture(
      "ServiceStatus",
      ServiceStatus(
        name = "cart",
        projectId = "shop",
        lifecycle = ServiceLifecycle.PartiallyReady,
        generation = 7,
        image = "cart:1.2.3",
        readyInstances = 1,
        desiredInstances = 2,
        detail = Some("1 of 2 instances ready"),
        confirmed = false,
        database = Some("provisioned"),
        hostname = Some("https://cart-shop.example.com"),
        exposed = true,
        suspended = true,
        paused = true,
        hosting = "process",
        protocol = Some("1.0"),
        mounts = Vector(MountStatus("/api/cart", "cart", "ok"), MountStatus("/admin", "admin")),
        callers = Vector("orders", "billing/invoices", "*"),
        processPort = Some(3000),
        broker = Some("provisioned"),
        undeclaredTopics = Some(Vector("cart-checkouts")),
        objectStorage = Some("provisioned"),
        bucket = Some("shop.cart"),
        bucketAddress = Some("https://storage.example.com/shop.cart")
      ),
      ServiceStatus("cart", "shop", ServiceLifecycle.NotDeployed, 0, "cart:1", 0, 0)
    ),
    fixture(
      "MountStatus",
      MountStatus("/api/cart", "cart", "no service"),
      MountStatus("/api/cart", "cart")
    ),
    // History is only ever answered as a list, which is the one codec Wire has for it.
    fixture(
      "HistoryEntry",
      Vector(
        HistoryEntry(
          "applied",
          3,
          Some(HistoryActor("sub-1", Some("Olive Owner"), administrative = true)),
          Some(at),
          image = Some("cart:1"),
          digest = Some("3f9a1c0be2d4" + "0" * 52),
          rolledBackTo = Some(1)
        )
      ),
      Vector(HistoryEntry("restarted", 1))
    ),
    fixture("RollbackRequest", RollbackRequest(Some(1)), RollbackRequest()),
    fixture(
      "RolledBack",
      RolledBack(
        1,
        ServiceStatus("cart", "shop", ServiceLifecycle.UpdateInProgress, 3, "cart:1", 0, 1)
      ),
      RolledBack(1, ServiceStatus("cart", "shop", ServiceLifecycle.NotDeployed, 0, "cart:1", 0, 0))
    ),
    fixture(
      "InstanceLogs",
      InstanceLogs("cart-0", "started\nserving\n", Some("container not found")),
      InstanceLogs("cart-0", "", None)
    ),
    fixture(
      "LogsResponse",
      LogsResponse(Vector(InstanceLogs("cart-0", "started\n", None))),
      LogsResponse(Vector.empty)
    ),
    fixture(
      "MembersResponse",
      MembersResponse(
        Vector(
          MemberSummary(
            "sub-1",
            Role.Owner,
            Some("owner@example.com"),
            Some("Olive Owner"),
            Some(at),
            Some("Dev User")
          )
        ),
        Vector(InvitationSummary("new@example.com", Role.Member, Some(at), Some("Olive Owner")))
      ),
      MembersResponse()
    ),
    fixture(
      "DeployTokenSummary",
      DeployTokenSummary(
        "abc123",
        "ci",
        "token:abc123",
        Some("Olive Owner"),
        Some(at),
        Some(later),
        Some(LocalDate.parse("2026-10-01"))
      ),
      DeployTokenSummary("abc123", "ci", "token:abc123")
    ),
    fixture(
      "DeployTokenCreated",
      DeployTokenCreated("abc123", "ci", "ankka_abc123_secret", "token:abc123", Some(later)),
      DeployTokenCreated("abc123", "ci", "ankka_abc123_secret", "token:abc123")
    ),
    fixture("CreateDeployToken", CreateDeployToken("ci", Some(86400L)), CreateDeployToken("ci")),
    fixture(
      "SetRegistry",
      SetRegistry("ghcr.io", "robot", "password"),
      SetRegistry("ghcr.io", "robot", "password")
    ),
    fixture(
      "TopicDeclarationRequest",
      TopicDeclarationRequest(
        12,
        compacted = true,
        contract = Some(
          ContractDeclaration(
            "transaction.v1",
            com.thinkmorestupidless.ankka.core.graph.GraphJson
              .parse("""{"type":"object","required":["id"]}""".getBytes("UTF-8"))
              .toOption
              .get
          )
        )
      ),
      TopicDeclarationRequest(1)
    ),
    fixture(
      "ProjectTopic",
      ProjectTopic(
        "transactions",
        12,
        Some("failed"),
        Some("the installation has no broker"),
        compacted = true,
        contract =
          Some(com.thinkmorestupidless.ankka.core.Contract("transaction.v1", "sha256:" + "a" * 64)),
        checks = Vector(
          TopicCheck(
            "transactions",
            "wallet",
            "consumer:relay",
            "publishes",
            Some("transaction.v1"),
            "checked"
          ),
          TopicCheck("transactions", "ledger", "view:by-day", "reads", None, "mismatch")
        )
      ),
      ProjectTopic("transactions", 12)
    ),
    fixture(
      "Contract",
      com.thinkmorestupidless.ankka.core.Contract("transaction.v1", "sha256:" + "a" * 64),
      com.thinkmorestupidless.ankka.core.Contract("transaction.v1", "sha256:" + "a" * 64)
    ),
    fixture(
      "BrokerDeclarationRequest",
      BrokerDeclarationRequest("kafka.legacy:9094", "sasl", "legacy-credential"),
      BrokerDeclarationRequest("kafka.legacy:9094", "certificate", "legacy-credential")
    ),
    fixture(
      "ProjectBroker",
      ProjectBroker(
        "legacy",
        "kafka.legacy:9094",
        "sasl",
        "legacy-credential",
        Some("2026-10-07T10:00:00Z")
      ),
      ProjectBroker("legacy", "kafka.legacy:9094", "sasl", "legacy-credential")
    ),
    fixture(
      "SetProjectSecret",
      SetProjectSecret(Map("STRIPE_KEY" -> "sk_live_1", "WEBHOOK_KEY" -> "whsec_1")),
      SetProjectSecret(Map("STRIPE_KEY" -> "sk_live_1"))
    ),
    fixture(
      "ProjectSecretSummary",
      ProjectSecretSummary(
        "checkout",
        Vector("STRIPE_KEY", "WEBHOOK_KEY"),
        Some(at),
        Some("Olive Owner")
      ),
      ProjectSecretSummary("checkout", Vector("STRIPE_KEY"))
    ),
    fixture("Rename", Rename("New name"), Rename("New name")),
    fixture("Invite", Invite("new@example.com", Role.Owner), Invite("new@example.com")),
    fixture("RoleChange", RoleChange(Role.Owner), RoleChange(Role.Member)),
    fixture("Repair", Repair(Role.Member), Repair()),
    fixture(
      "CreateOrganization",
      CreateOrganization(
        "Acme",
        Some(Owner("sub-1", Some("owner@example.com"), Some("Olive Owner")))
      ),
      CreateOrganization("Acme")
    ),
    fixture("CreateProject", CreateProject("Shop", "acme"), CreateProject("Shop", "acme")),
    // Opaque to the console, which forwards a descriptor as the text the person gave it; emitted so
    // the list is whole and the TypeScript side can say it is deliberately not decoded.
    fixture(
      "ServiceDescriptor",
      ServiceDescriptor(
        "cart",
        ServiceSpec("cart:1", runtime = Some("0.7.0"), grpc = true, provisionObjectStorage = true)
      ),
      ServiceDescriptor("cart", ServiceSpec("cart:1"))
    ),
    fixture(
      "ServiceSpec",
      ServiceSpec("cart:1", runtime = Some("0.7.0"), grpc = true, provisionObjectStorage = true),
      ServiceSpec("cart:1")
    ),
    fixture(
      "ServiceTopology",
      ServiceTopology(
        "cart",
        running = 2,
        contributing = 1,
        partial = true,
        instances = Vector(
          InstanceTopology(
            "cart-7d9f-abc",
            InstanceStatus.Ok,
            None,
            Some("0.10.0"),
            Some(at.toString)
          ),
          InstanceTopology(
            "cart-7d9f-def",
            InstanceStatus.Unsupported,
            Some("this instance's runtime (0.9.2) serves no topology")
          )
        ),
        window = TopologyWindow(600L, at.toString, 14L, 1L),
        nodes = Vector(
          TopologyNode(
            "endpoint:/carts",
            "Endpoint",
            0,
            platform = false,
            Vector(TopologyHandler("POST /carts/{cartId}/items", "route", Some(false)))
          ),
          TopologyNode(
            "cart",
            "EventSourcedEntity",
            1,
            platform = false,
            Vector(TopologyHandler("add-item", "command"), TopologyHandler("get-cart", "query"))
          ),
          TopologyNode("carts-by-customer", "View", 2, platform = false, Vector.empty)
        ),
        declared = Vector(DeclaredEdge("cart", "carts-by-customer", "events")),
        calls = Vector(
          CallEdge(
            "endpoint:/carts",
            "cart",
            Vector(
              CallPair(
                "POST /carts/{cartId}/items",
                "add-item",
                HandledCounts(12L, 1L, 0L),
                UnansweredCounts(1L, 0L),
                DurationMillis(1.0, 4.0, 4.0),
                streaming = false
              )
            )
          )
        ),
        differences = Vector(TopologyDifference("carts-by-customer", Vector("cart-7d9f-abc")))
      ),
      ServiceTopology(
        "cart",
        running = 0,
        contributing = 0,
        partial = false,
        instances = Vector.empty,
        window = TopologyWindow(600L, at.toString, 0L),
        nodes = Vector.empty,
        declared = Vector.empty,
        calls = Vector.empty,
        differences = Vector.empty
      )
    ),
    fixture(
      "InstanceTopology",
      InstanceTopology(
        "cart-7d9f-abc",
        InstanceStatus.Failed,
        Some("the body is not a topology"),
        Some("0.10.0"),
        Some(at.toString)
      ),
      InstanceTopology("cart-7d9f-abc", InstanceStatus.Ok)
    ),
    fixture(
      "InstanceTopologyDocument",
      InstanceTopologyDocument(
        TopologyService("cart", "0.10.0", "4242", at.toString),
        TopologyWindow(600L, at.toString, 2L, 0L),
        Vector(TopologyNode("cart", "EventSourcedEntity", 1, platform = false, Vector.empty)),
        Vector.empty,
        Vector(
          CallEdge(
            "endpoint:/carts",
            "cart",
            Vector(
              CallPair(
                "POST /carts/{cartId}/items",
                "add-item",
                HandledCounts(2L, 0L, 0L),
                UnansweredCounts(0L, 0L),
                DurationMillis(1.0, 1.0, 1.0),
                streaming = false,
                histogram = Vector.fill(32)(0L).updated(10, 2L)
              )
            )
          )
        )
      ),
      InstanceTopologyDocument(
        TopologyService("cart", "0.10.0", "1", at.toString),
        TopologyWindow(600L, at.toString, 0L),
        Vector.empty,
        Vector.empty,
        Vector.empty
      )
    )
  ).flatten

  test("every fixture matches what the wire codecs write") {
    if update then Files.createDirectories(dir): Unit
    val stale = fixtures.flatMap { (file, content) =>
      val path = dir.resolve(file)
      if update then
        Files.writeString(path, content, UTF_8)
        None
      else if !Files.exists(path) || Files.readString(path, UTF_8) != content then Some(file)
      else None
    }
    assert(
      stale.isEmpty,
      s"the wire format changed for ${stale.mkString(", ")}; if that is intended, rerun with " +
        "-Dankka.docs.update=true and update the console's schemas to match"
    )
  }

  test("no fixture exists that the codecs no longer write") {
    if !update && Files.isDirectory(dir) then
      val written = fixtures.map(_._1).toSet
      val orphans = Files
        .list(dir)
        .iterator
        .asScala
        .map(_.getFileName.toString)
        .filter(_.endsWith(".json"))
        .filterNot(written)
        .toVector
      assertEquals(orphans, Vector.empty, "fixture files no codec writes")
  }

  test("every codec in Wire has a fixture") {
    val source = Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(
        _.resolve(
          "controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/descriptors.scala"
        )
      )
      .find(Files.exists(_))
      .map(Files.readString(_, UTF_8))
      .getOrElse(fail("could not find descriptors.scala"))
    val wire = source.substring(source.indexOf("object Wire:"))
    val types =
      "JsonValueCodec\\[(?:Vector\\[)?([A-Za-z]+)\\]".r.findAllMatchIn(wire).map(_.group(1)).toSet
    val named = fixtures.map(_._1.takeWhile(_ != '.')).toSet
    assertEquals(types -- named, Set.empty[String], "codecs in Wire with no fixture")
  }
