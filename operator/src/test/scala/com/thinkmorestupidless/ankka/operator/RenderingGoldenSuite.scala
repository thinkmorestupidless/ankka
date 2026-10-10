package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaSerialization, AnkkaService, AnkkaServiceSpec}
import io.fabric8.kubernetes.api.model.{HasMetadata, ObjectMetaBuilder}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/**
 * Everything the operator renders for six kinds of service, compared byte for byte with a record
 * committed before gRPC endpoints existed.
 *
 * A field added to the resource must change nothing for a service that does not set it: no object
 * the operator applies may differ, or every existing service is re-applied and, if the pod template
 * moved, every instance restarted on the next reconcile after an upgrade. A suite that asserts on
 * the fields it knows about cannot see a change in a field it does not, so this one asserts on
 * everything.
 *
 * `-Dankka.golden.update=true` rewrites the record instead of comparing. Do that only for a change
 * that is meant to alter what an existing service renders, and say so in the commit.
 */
class RenderingGoldenSuite extends munit.FunSuite:

  private val serialization = AnkkaSerialization()
  private val pretty        = AnkkaSerialization.mapper().writerWithDefaultPrettyPrinter()
  private val directory     = Paths.get("src/test/resources/golden")
  private val update        = sys.props.get("ankka.golden.update").contains("true")

  private val base = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 4L,
    image = "registry.example.com/acme/cart:1.4.2",
    port = Some(9000)
  )

  private val provisioning =
    ProvisioningPlan.Waiting(
      needsCluster = true,
      needsRole = true,
      needsDatabase = true,
      detail = None
    )

  private val cases: Vector[(String, AnkkaServiceSpec, Settings, ProvisioningPlan)] = Vector(
    ("embedded", base, Settings.default, provisioning),
    ("no-port", base.copy(port = None), Settings.default, provisioning),
    (
      "exposed",
      base.copy(exposed = true),
      Settings.default.copy(baseDomain = Some("example.test")),
      ProvisioningPlan.Ready(recovered = false)
    ),
    ("process", base.copy(hosting = "process"), Settings.default, provisioning),
    ("wasm", base.copy(hosting = "wasm"), Settings.default, provisioning),
    (
      "supplied-database",
      base.copy(provisionDatabase = false),
      Settings.default,
      ProvisioningPlan.Supplied
    )
  )

  /**
   * A service in an installation with a broker (feature 027): its user, its certificate's common
   * name and the variables that tell it where the broker is. Its project's topics are the project's
   * resource's, rendered by `ProjectReconciler`.
   */
  private val brokerCases: Vector[(String, AnkkaServiceSpec, Settings, ProvisioningPlan)] = Vector(
    (
      "broker",
      base.copy(provisionDatabase = false),
      Settings.default.copy(broker = Some(BrokerStack.settings)),
      ProvisioningPlan.Supplied
    )
  )

  private val store = ObjectStoreSettings(
    "http://garage.garage-system.svc.cluster.local:3903",
    "the-admin-token",
    "http://garage.garage-system.svc.cluster.local:3900",
    "garage",
    ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
  )

  /**
   * A service with a bucket (feature 034). The record is read for a secret too: neither the store's
   * token nor any key may appear in it.
   */
  private val storageCase =
    (
      "object-storage",
      base.copy(provisionObjectStorage = true),
      Settings.default.copy(objectStore = Some(store)),
      provisioning
    )

  private def storagePlanOf(name: String): ObjectStoragePlan =
    if name == storageCase._1 then ObjectStoragePlan.Ready(recovered = false)
    else ObjectStoragePlan.NotAsked

  (cases ++ brokerCases :+ storageCase).foreach { (name, spec, settings, plan) =>
    test(s"what is rendered for '$name' is what was rendered before") {
      val rendered = render(spec, settings, plan, storagePlanOf(name))
      val file     = directory.resolve(s"$name.txt")
      if update then
        Files.createDirectories(directory)
        Files.writeString(file, rendered, UTF_8)
      else
        assert(Files.exists(file), s"no record at $file; run with -Dankka.golden.update=true once")
        assertNoDiff(rendered, Files.readString(file, UTF_8))
    }
  }

  /**
   * A service whose bucket is in the installation's cloud account (feature 044), once its cloud
   * provider has answered all three requests: the requests themselves, the variables from the
   * answers, the credential's generation on the pod template, and nothing of the installation's own
   * store.
   */
  test("what is rendered for 'cloud-bucket' is what was rendered before") {
    val cloud = CloudSettings(
      "gcp",
      "acct",
      "europe-west2",
      Some("keys/ankka"),
      scala.concurrent.duration.Duration(2, "minutes"),
      scala.concurrent.duration.Duration(1, "hour")
    )
    val spec     = base.copy(provisionObjectStorage = true)
    val settings = Settings.default.copy(cloud = Some(cloud))
    val plan = ObjectStoragePlan.Ready(
      recovered = false,
      Some(
        CloudBucket("acct-checkout-cart", "https://storage.scripted.invalid", "europe-west2", 1L)
      )
    )
    val rendered = render(
      spec,
      settings,
      provisioning,
      plan,
      resource =>
        ObjectStorage.cloudRequests(
          resource,
          settings,
          cloud,
          None,
          Some("cart@acct.scripted"),
          Some("acct-checkout-cart")
        )
    )
    assert(rendered.contains("# cloud request identity"), rendered)
    assert(rendered.contains("# cloud request bucket-credential"), rendered)
    assert(!rendered.contains("# ensure bucket"), "the installation's own store is not asked")
    val file = directory.resolve("cloud-bucket.txt")
    if update then Files.writeString(file, rendered, UTF_8)
    else
      assert(Files.exists(file), s"no record at $file; run with -Dankka.golden.update=true once")
      assertNoDiff(rendered, Files.readString(file, UTF_8))
  }

  test("no record but the cloud bucket's holds a cloud request") {
    // absent.feature: "an installation with no cloud provider serves everything itself".
    Files.list(directory).iterator.asScala.foreach { file =>
      val text = Files.readString(file, UTF_8)
      if file.getFileName.toString == "cloud-bucket.txt" then assert(text.contains("cloud request"))
      else assert(!text.contains("cloud request"), s"$file renders a cloud request")
    }
  }

  test("nothing rendered for a service with a bucket holds the store's token") {
    val (name, spec, settings, plan) = storageCase
    assert(!render(spec, settings, plan, storagePlanOf(name)).contains("the-admin-token"))
  }

  private def render(
      spec: AnkkaServiceSpec,
      settings: Settings,
      plan: ProvisioningPlan,
      storagePlan: ObjectStoragePlan,
      cloudRequests: AnkkaService => Vector[com.thinkmorestupidless.ankka.crd.CloudResource] = _ =>
        Vector.empty
  ): String =
    val resource = new AnkkaService
    resource.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace("ankka-checkout")
        .withName(spec.serviceName)
        .withUid("uid-1")
        .build()
    )
    resource.setSpec(spec)
    Rendering.render(
      resource,
      settings,
      plan,
      BrokerProvisioning.known(spec, settings.broker),
      storagePlan,
      cloudRequests = cloudRequests(resource)
    ) match
      case Left(problems) => fail(s"rendering failed: ${problems.mkString("; ")}")
      case Right(actions) => actions.map(document).mkString("\n")

  /**
   * One entry per action: what it does, then the whole object it applies, as the JSON the
   * operator's own serializer writes, indented so a difference reads as a line. (fabric8's YAML
   * writer cannot represent the Scala maps inside a certificate's generic spec; its JSON writer is
   * the one that reaches the API server anyway.) An action that removes carries no object, so its
   * description is all there is to compare.
   */
  private def document(action: Action): String =
    val body = objectOf(action)
      .map(o =>
        pretty.writeValueAsString(
          AnkkaSerialization.mapper().readTree(serialization.asJson(o))
        ) + "\n"
      )
      .getOrElse("")
    s"# ${action.describe}\n$body"

  private def objectOf(action: Action): Option[HasMetadata] = action match
    case Action.ApplyDeployment(d)        => Some(d)
    case Action.EnsureService(s)          => Some(s)
    case Action.EnsureGrpcPeers(s, _)     => Some(s)
    case Action.EnsureHttpRoute(r)        => Some(r)
    case Action.EnsureServiceAccount(sa)  => Some(sa)
    case Action.EnsureRole(r)             => Some(r)
    case Action.EnsureRoleBinding(b)      => Some(b)
    case Action.EnsureCluster(c)          => Some(c)
    case Action.EnsureCredentials(s)      => Some(s)
    case Action.EnsureDatabaseRole(r)     => Some(r)
    case Action.EnsureDatabase(d)         => Some(d)
    case Action.EnsureSchemaConfig(cm)    => Some(cm)
    case Action.EnsureCertificate(c)      => Some(c)
    case Action.EnsureIssuer(i)           => Some(i)
    case Action.EnsureNetworkPolicy(p)    => Some(p)
    case Action.EnsureBackendTlsPolicy(p) => Some(p)
    case Action.EnsureKafkaUser(u)        => Some(u)
    case Action.EnsureKafkaTopic(t)       => Some(t)
    case Action.EnsureCloudResource(r)    => Some(r)
    case Action.EnsureReferenceGrant(g)   => Some(g)
    case _                                => None
