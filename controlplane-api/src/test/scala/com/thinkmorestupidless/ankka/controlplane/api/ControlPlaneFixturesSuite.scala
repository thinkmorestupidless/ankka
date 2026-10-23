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

  private val backupLine = BackupLine(
    "ankka-db",
    "ankka-db",
    "backing up",
    Some(at),
    Some(at),
    Some(at),
    Some(12.0),
    None,
    None
  )
  private val verification =
    ServiceVerification("wallet", present = true, 12L, 1L, 4L, 0L, 12L, Vector("api-key"))
  private val restoreView = RestoreView(
    "ankka-db-r202610081012",
    "ankka-db",
    at,
    "Verified",
    Some("Olive Owner"),
    Some(at),
    Some(at),
    Vector(verification, ServiceVerification("rewards", present = false)),
    None,
    Vector(
      TopicDivergence(
        "transactions",
        Some("ankka.shop.totals.view.sums"),
        3L,
        Some(2L),
        Vector("ledger")
      )
    ),
    Vector("totals"),
    Some(BackupPhrases.Republished)
  )
  private val rehearsalView = RehearsalView(
    "ankka-db-x202610090000",
    "ankka-db",
    at,
    "Completed",
    Some("Olive Owner"),
    Some(at),
    Some(118L),
    None,
    Vector(verification)
  )

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
        Some(CloudInstallation("gcp", "acme-production", "europe-west2", Some("keys/ankka"))),
        secrets =
          Some(InstallationSecrets("secret-manager", "365d", InstallationSecrets.AuditLogOn)),
        models = Some(
          InstallationModels(
            Vector(
              ClusterModels(
                "home",
                "https://models.models-system.svc:8443",
                ready = true,
                Vector(
                  ModelLine("qwen3:0.6b", "served", Some("7d4f3a1c9b2e8f60")),
                  ModelLine(
                    "no-such-model:1b",
                    "failed",
                    detail = Some("pull model manifest: file does not exist")
                  )
                )
              )
            )
          )
        ),
        broker = Some(InstallationBroker(Some(3), defaultCopies = 3, minInSync = 2)),
        retention = Some(InstallationRetention(Some("30d")))
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
        secretStore = Some(
          SecretStoreReport(
            "secret-manager",
            keyRead = true,
            Some(
              SecretMoveReport(
                "remove",
                "refused",
                Vector(SecretMoveName("acme", "equal"), SecretMoveName("stripe", "different")),
                Some("not removed: stripe differs from Secret Manager")
              )
            )
          )
        ),
        topicSources = Some(
          Vector(
            TopicSourceReport(
              "view",
              "entries",
              "transactions",
              "ankka.shop.cart.view-v2.entries",
              "earliest",
              2,
              lag = Some(4L),
              gap = Some(
                RetentionGapReport(
                  Vector(
                    PartitionGapReport(0, 1240L, Some(at)),
                    PartitionGapReport(1, 0L)
                  ),
                  compacted = false,
                  gone = true,
                  readAt = Some(later)
                )
              )
            )
          )
        ),
        warnings = Some(
          Vector(
            ServiceWarning(
              "retention",
              "entries",
              "transactions",
              "view 'entries' reads topic 'transactions', which keeps 7d; the installation warns below 30d"
            )
          )
        ),
        objectStorage = Some("provisioned"),
        bucket = Some("shop.cart"),
        bucketAddress = Some("https://storage.example.com/shop.cart"),
        objectStore = Some("gcs"),
        bucketLocation = Some("europe-west2"),
        softDeleteDays = Some(7),
        storageMove = Some("write pause"),
        buckets = Vector(
          BucketReport(
            "photos",
            "Provisioned",
            Some("garage"),
            Some("shop.cart.photos"),
            "public",
            Some("https://storage.example.com/shop.cart.photos")
          ),
          BucketReport("archive", "Supplied", None, Some("acme-archive"), "supplied"),
          BucketReport(
            "old-photos",
            "Kept",
            Some("garage"),
            Some("shop.cart.old-photos"),
            kept = true,
            detail = Some("no longer named; kept")
          )
        ),
        grants = Some("mounted"),
        customHostnames = Vector(
          CustomHostname(
            "app.example.com",
            "serving",
            record = Some(DnsRecord("app.example.com", "CNAME", "cart-shop.example.com"))
          ),
          CustomHostname(
            "example.com",
            "pending",
            Some("the certificate is being issued"),
            note = Some("an apex cannot be a CNAME; this installation has published no address")
          )
        ),
        proofRecord = Some(DnsRecord("_ankka.<hostname>", "TXT", "ankka-project=shop"))
      ),
      ServiceStatus("cart", "shop", ServiceLifecycle.NotDeployed, 0, "cart:1", 0, 0)
    ),
    fixture(
      "SecretReadsPage",
      SecretReadsPage(
        Vector(
          com.thinkmorestupidless.ankka.core.secrets.ReadRecord(
            at,
            "shop",
            "cart",
            "embedded",
            "psp/acme/api-key",
            "get",
            "read",
            "secret-manager",
            Some("4bf92f3577b34da6a3ce929d0e0e4736"),
            Some("00f067aa0ba902b7"),
            Some("charge"),
            Some("workflow"),
            latestSkipped = true
          )
        )
      ),
      SecretReadsPage(Vector.empty)
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
        ),
        HistoryEntry("hostname added", 3, hostname = Some("app.example.com"))
      ),
      Vector(HistoryEntry("restarted", 1))
    ),
    fixture("RollbackRequest", RollbackRequest(Some(1)), RollbackRequest()),
    fixture("StorageMoveRequest", StorageMoveRequest(Some("30m")), StorageMoveRequest()),
    fixture(
      "SetProjectLocation",
      SetProjectLocation("europe-west6"),
      SetProjectLocation("europe-west6")
    ),
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
        Some(12),
        compacted = true,
        retention = Some("90d"),
        retentionSize = Some("50GiB"),
        cleanup = Some("compact,delete"),
        tombstoneWindow = Some("1d"),
        minCompactionLag = Some("1h"),
        maxCompactionLag = Some("none"),
        copies = Some(3),
        minInSync = Some(2),
        removes = Some("messages older than 90d"),
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
      TopicDeclarationRequest(Some(1))
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
        ),
        settings = Some(
          TopicSettingsView(
            "90d",
            "none",
            "compact",
            "1d",
            "0s",
            "none",
            Some(3),
            Some(2),
            Vector("retentionSize", "tombstoneWindow")
          )
        ),
        copiesHeld = Some(3),
        brokerNodes = Some(3)
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
          // An MCP endpoint's tools are its node's handlers, of type tool, beside its route.
          TopologyNode(
            "endpoint:/mcp",
            "Endpoint",
            0,
            platform = false,
            Vector(
              TopologyHandler("POST /mcp", "route", Some(false)),
              TopologyHandler("get_cart", "tool")
            )
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
    // Feature 041: backups, restores, switches, rehearsals and the project's database.
    fixture(
      "ProjectStatus",
      ProjectStatus(
        "shop",
        backedUp = true,
        "object-store",
        Vector(backupLine),
        Some(ProjectDatabase("ankka-db", 3, 3, Some("ankka-db-1"), synchronous = true, None)),
        None,
        Vector(
          ProjectCluster("ankka-db", "ankka-db", "left", Vector.empty, None, Some(at)),
          ProjectCluster(
            "ankka-db-r202610081012",
            "ankka-db-r202610081012",
            "restore",
            Vector("wallet"),
            Some(at)
          )
        ),
        Vector(restoreView),
        Some(rehearsalView)
      ),
      ProjectStatus("shop", backedUp = false, "none", detail = Some(BackupPhrases.NoTarget))
    ),
    fixture(
      "InstallationStatus",
      InstallationStatus(
        "object-store",
        30,
        copyRequired = true,
        sharesFailureDomain = false,
        BackupPhrases.GarageEncryption,
        Some(backupLine),
        None
      ),
      InstallationStatus(
        "none",
        30,
        copyRequired = false,
        sharesFailureDomain = true,
        "none",
        notBackedUp = Some(BackupPhrases.NoTarget)
      )
    ),
    fixture("RestoreRequest", RestoreRequest(at, Some("ankka-db")), RestoreRequest(at)),
    fixture(
      "RestoreView",
      restoreView,
      RestoreView("ankka-db-r202610081012", "ankka-db", at, "Restoring")
    ),
    fixture("SwitchRequest", SwitchRequest("ankka-db-r202610081012"), SwitchRequest("ankka-db")),
    fixture(
      "ProjectHistoryView",
      // Every field, though no entry has both a detail and a topic: the schema reads each.
      ProjectHistoryView(
        "topic-changed",
        Some("Olive Owner"),
        Some(at),
        Some("ankka-db-r202610081012"),
        Some("transactions"),
        Vector(SettingChange(Setting.Retention, "90d", "180d"))
      ),
      ProjectHistoryView("database-set")
    ),
    fixture(
      "DatabaseSetting",
      DatabaseSetting(2, synchronous = true, Some(45), Some("weekly")),
      DatabaseSetting()
    ),
    fixture(
      "RestoreHoldStatus",
      RestoreHoldStatus(
        held = true,
        Some(at),
        Some(at),
        None,
        None,
        Vector(
          ServiceDifference("checkout", "cart", Some(1L), Some(2L), Some("cart:1"), Some("cart:2"))
        ),
        Vector("checkout"),
        Vector("lab"),
        Vector("the erasure log is up to date: 0 gained")
      ),
      RestoreHoldStatus(held = false)
    ),
    fixture("CredentialReissued", CredentialReissued("shop", 2), CredentialReissued("shop", 1)),
    fixture("RehearsalRequest", RehearsalRequest(Some(at), Some("ankka-db")), RehearsalRequest()),
    fixture(
      "RehearsalView",
      rehearsalView,
      RehearsalView("ankka-db-x202610090000", "ankka-db", at, "Running")
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
  ).flatten ++ grantFixtures

  private def grantFixtures: Vector[(String, String)] =
    val route   = GrantTarget.route("wallet", "POST", "/v1/wallets/{player}/{currency}/deposits")
    val topic   = GrantTarget.topic("casino.players", GrantTarget.Consume, decrypt = true)
    val ada     = GrantAct(Some("Ada Owner"), Some(at))
    val bo      = GrantAct(Some("Bo Owner"), Some(later))
    val rates   = ByteRatesRequest(1048576L, 4194304L, 50)
    val machine = Grantee.Machine("affiliates", "network")
    Vector(
      fixture(
        "GrantRequest",
        GrantRequest("service:payments/merchant", route),
        GrantRequest("service:payments/merchant", GrantTarget.erasure)
      ),
      fixture(
        "GrantDetail",
        GrantDetail(
          "3f9c0a1b2c3d4e5f",
          machine,
          topic,
          GrantState.Revoked,
          "revoked",
          ada,
          Some(bo),
          Some(ada)
        ),
        GrantDetail(
          "3f9c0a1b2c3d4e5f",
          Grantee.Service("payments", "merchant"),
          route,
          GrantState.Accepted,
          "in effect",
          GrantAct()
        )
      ),
      fixture(
        "ReceivedGrantDetail",
        ReceivedGrantDetail(
          "3f9c0a1b2c3d4e5f",
          "spinvibe",
          "eitheror",
          machine,
          topic,
          GrantState.Accepted,
          Vector(
            GrantChangeRecord(GrantChange.Offered, Some("Ada Owner"), Some(at)),
            GrantChangeRecord(GrantChange.Accepted, Some("Bo Owner"), Some(later))
          ),
          Some(GrantedTopicSettings(3, compacted = true, Some("7d"), Some(3)))
        ),
        ReceivedGrantDetail(
          "3f9c0a1b2c3d4e5f",
          "spinvibe",
          "eitheror",
          Grantee.Service("payments", "merchant"),
          route,
          GrantState.Pending
        )
      ),
      fixture(
        "MachineRegistration",
        MachineRegistration("network"),
        MachineRegistration("network")
      ),
      fixture(
        "MachineRegistered",
        MachineRegistered(
          "network",
          "machine:affiliates/network",
          "c0ffee" * 10 + "c0ff",
          "https://api.example.com/oauth/token",
          Some("broker.example.com:9094")
        ),
        MachineRegistered("network", "machine:affiliates/network", "s", "https://api/oauth/token")
      ),
      fixture(
        "MachineSummary",
        MachineSummary(
          "network",
          "machine:affiliates/network",
          Some("Bo Owner"),
          Some(at),
          Some(rates)
        ),
        MachineSummary("network", "machine:affiliates/network")
      ),
      fixture("ByteRatesRequest", rates, ByteRatesRequest(1L, 1L, 1)),
      fixture(
        "TokenResponse",
        TokenResponse("eyJ.e30.sig", "Bearer", 900L),
        TokenResponse("t", "Bearer", 900L)
      ),
      fixture(
        "JsonWebKeySet",
        JsonWebKeySet(Vector(JsonWebKey("RSA", "sig", "RS256", "20261009000000ab12", "n", "AQAB"))),
        JsonWebKeySet(Vector.empty)
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
    val wire  = source.substring(source.indexOf("object Wire:"))
    val types =
      "JsonValueCodec\\[(?:Vector\\[)?([A-Za-z]+)\\]".r.findAllMatchIn(wire).map(_.group(1)).toSet
    val named = fixtures.map(_._1.takeWhile(_ != '.')).toSet
    assertEquals(types -- named, Set.empty[String], "codecs in Wire with no fixture")
  }
