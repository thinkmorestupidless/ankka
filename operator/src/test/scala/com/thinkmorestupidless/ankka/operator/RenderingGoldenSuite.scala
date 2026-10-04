package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaSerialization,
  AnkkaService,
  AnkkaServiceSpec,
  TopicEntry
}
import io.fabric8.kubernetes.api.model.{HasMetadata, ObjectMetaBuilder}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

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
      needsCredentials = true,
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
   * A service with a declared topic, in an installation with a broker (feature 027): its user, its
   * topic, its certificate's common name and the variables that tell it where the broker is.
   */
  private val brokerCases: Vector[(String, AnkkaServiceSpec, Settings, ProvisioningPlan)] = Vector(
    (
      "broker",
      base.copy(topics = List(TopicEntry("orders", 3)), provisionDatabase = false),
      Settings.default.copy(broker = Some(BrokerStack.settings)),
      ProvisioningPlan.Supplied
    )
  )

  (cases ++ brokerCases).foreach { (name, spec, settings, plan) =>
    test(s"what is rendered for '$name' is what was rendered before") {
      val rendered = render(spec, settings, plan)
      val file     = directory.resolve(s"$name.txt")
      if update then
        Files.createDirectories(directory)
        Files.writeString(file, rendered, UTF_8)
      else
        assert(Files.exists(file), s"no record at $file; run with -Dankka.golden.update=true once")
        assertNoDiff(rendered, Files.readString(file, UTF_8))
    }
  }

  private def render(spec: AnkkaServiceSpec, settings: Settings, plan: ProvisioningPlan): String =
    val resource = new AnkkaService
    resource.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace("ankka-checkout")
        .withName(spec.serviceName)
        .withUid("uid-1")
        .build()
    )
    resource.setSpec(spec)
    val topics = BrokerProvisioning.topicsToRender(spec, settings.broker, BrokerObservation.empty)
    Rendering.render(resource, settings, plan, topics) match
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
    case _                                => None
