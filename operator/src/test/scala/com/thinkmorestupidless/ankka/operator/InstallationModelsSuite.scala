package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.{
  ModelServerDocument,
  ModelState,
  ModelStatus,
  PlatformVariables
}
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec, EnvEntry, ServedModel}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder

import scala.jdk.CollectionConverters.*

object InstallationModelsSuite:
  val Server: ModelServerSettings = ModelServerSettings(
    "https://models.models-system.svc:8443",
    "http://models.models-system.svc:8080/ankka/models",
    "models-system"
  )

/**
 * What the operator decides about a service's installation models (feature 053): which it names,
 * whether this cluster serves them, and in which words a member is told why not.
 */
class InstallationModelsSuite extends munit.FunSuite:
  import InstallationModelsSuite.Server

  private val base =
    AnkkaServiceSpec(projectId = "shop", serviceName = "cart", generation = 1L, image = "cart:1")

  private def naming(entries: (String, String)*): AnkkaServiceSpec =
    base.copy(env = entries.map((n, v) => EnvEntry(n, Some(v))).toList)

  private val local =
    naming("ANKKA_MODEL_LOCAL_PRESET" -> "installation", "ANKKA_MODEL_LOCAL_MODEL" -> "qwen3:0.6b")

  private val digest = "7d4f3a1c9b2e8f60aa11223344556677889900aabbccddeeff00112233445566"

  private def document(lines: ModelStatus*): Either[String, ModelServerDocument] =
    Right(ModelServerDocument(Server.address, ready = true, models = lines.toVector))

  private val served   = ModelStatus("qwen3:0.6b", ModelState.Served, Some(digest))
  private val fetching = ModelStatus("llama3.2:1b", ModelState.Fetching)

  private def decide(spec: AnkkaServiceSpec, observed: Either[String, ModelServerDocument]) =
    InstallationModels.decide(InstallationModels.named(spec), Some(Server), observed)

  test("a service that names no installation model is not affected, and the status is not read") {
    var read = false
    val plan = InstallationModels.decide(
      InstallationModels.named(naming("ANKKA_MODEL_CLAUDE_PRESET" -> "anthropic")),
      Some(Server),
      { read = true; document(served) }
    )
    assertEquals(plan, InstallationModelsPlan.NotNeeded)
    assert(!read, "the model server's status was read for a service that names none")
    assertEquals(InstallationModels.status(plan), None)
  }

  test(
    "a provider's preset names it, its model is required, and the provider's name is lowercase"
  ) {
    assertEquals(
      InstallationModels.named(local).map(n => (n.provider, n.model)),
      Vector("local" -> Some("qwen3:0.6b"))
    )
    assertEquals(
      InstallationModels.named(naming("ANKKA_MODEL_LOCAL_PRESET" -> "Installation")).size,
      1
    )
  }

  test("an agent on a served model starts, with its digest in the status") {
    val plan = decide(local, document(served, fetching))
    assertEquals(plan, InstallationModelsPlan.Ready(Vector(ServedModel("qwen3:0.6b", digest))))
    assertEquals(InstallationModels.withheld(plan), None)
    assertEquals(InstallationModels.status(plan).map(_.phase), Some("Ready"))
  }

  test("a model named without its version is the model served as :latest") {
    val plan = decide(
      naming("ANKKA_MODEL_LOCAL_PRESET" -> "installation", "ANKKA_MODEL_LOCAL_MODEL" -> "llama3"),
      document(ModelStatus("llama3:latest", ModelState.Served, Some(digest)))
    )
    assertEquals(plan, InstallationModelsPlan.Ready(Vector(ServedModel("llama3", digest))))
  }

  private def detail(
      spec: AnkkaServiceSpec,
      observed: Either[String, ModelServerDocument]
  ): String =
    decide(spec, observed) match
      case InstallationModelsPlan.Failed(why)  => s"failed: $why"
      case InstallationModelsPlan.Waiting(why) => s"waiting: $why"
      case other                               => other.toString

  test("each reason a service is held back, in the words a member reads") {
    def with_(model: String) =
      naming("ANKKA_MODEL_LOCAL_PRESET" -> "installation", "ANKKA_MODEL_LOCAL_MODEL" -> model)
    val cases = Vector(
      (
        with_("mistral:7b"),
        document(served),
        "failed: installation model \"mistral:7b\" is not served; served: qwen3:0.6b"
      ),
      (
        with_("mistral:7b"),
        document(),
        "failed: installation model \"mistral:7b\" is not served; served: none"
      ),
      (
        with_("llama3.2:1b"),
        document(served, fetching),
        "waiting: installation model \"llama3.2:1b\" is still being fetched"
      ),
      (
        with_("no-such-model:1b"),
        document(
          ModelStatus(
            "no-such-model:1b",
            ModelState.Failed,
            detail = Some("pull model manifest: file does not exist")
          )
        ),
        "failed: installation model \"no-such-model:1b\" failed: pull model manifest: file does not exist"
      ),
      (
        with_("old:1b"),
        document(ModelStatus("old:1b", ModelState.Removing)),
        "failed: installation model \"old:1b\" is not served; served: none"
      ),
      (local, Left("connection refused"), "waiting: the model server is not answering"),
      (
        naming(
          "ANKKA_MODEL_LOCAL_PRESET" -> "installation",
          "ANKKA_MODEL_LOCAL_MODEL"  -> "qwen3:0.6b",
          "ANKKA_MODEL_LOCAL_URL"    -> "http://ollama:11434"
        ),
        document(served),
        "failed: an installation model takes no address (ANKKA_MODEL_LOCAL_URL)"
      ),
      (
        naming(
          "ANKKA_MODEL_LOCAL_PRESET" -> "installation",
          "ANKKA_MODEL_LOCAL_MODEL"  -> "qwen3:0.6b",
          "ANKKA_MODEL_LOCAL_KEY"    -> "sk-nothing"
        ),
        document(served),
        "failed: an installation model takes no key (ANKKA_MODEL_LOCAL_KEY)"
      ),
      (
        naming("ANKKA_MODEL_LOCAL_PRESET" -> "installation"),
        document(served),
        "failed: an installation model is named by ANKKA_MODEL_LOCAL_MODEL, which is not set"
      )
    )
    for (spec, observed, expected) <- cases do assertEquals(detail(spec, observed), expected)
  }

  test("a cluster with no model server fails a service that names one, and reads nothing") {
    val plan = InstallationModels.decide(InstallationModels.named(local), None, fail("read"))
    assertEquals(plan, InstallationModelsPlan.Failed("this cluster has no model server"))
    assertEquals(InstallationModels.refused(plan), Some("this cluster has no model server"))
  }

  test("a failure says more than a wait, whichever model it is about") {
    val spec = naming(
      "ANKKA_MODEL_A_PRESET" -> "installation",
      "ANKKA_MODEL_A_MODEL"  -> "llama3.2:1b",
      "ANKKA_MODEL_B_PRESET" -> "installation",
      "ANKKA_MODEL_B_MODEL"  -> "mistral:7b"
    )
    assert(
      detail(spec, document(served, fetching)).startsWith(
        "failed: installation model \"mistral:7b\""
      )
    )
  }

  // ── rendering ──

  private def rendered(spec: AnkkaServiceSpec, settings: Settings, hold: Option[String] = None) =
    val resource = new AnkkaService
    resource.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-shop").withName("cart").withUid("u").build()
    )
    resource.setSpec(spec)
    Rendering
      .render(resource, settings, ProvisioningPlan.Supplied, modelsHold = hold)
      .fold(p => fail(p.mkString), identity)

  private def deploymentOf(actions: Vector[Action]) =
    actions.collectFirst { case Action.ApplyDeployment(d) => d }

  private def variablesByContainer(
      spec: AnkkaServiceSpec,
      settings: Settings
  ): Map[String, Vector[String]] =
    deploymentOf(rendered(spec, settings))
      .getOrElse(fail("no Deployment rendered"))
      .getSpec
      .getTemplate
      .getSpec
      .getContainers
      .asScala
      .map(c => c.getName -> c.getEnv.asScala.toVector.map(_.getName))
      .toMap

  private val withServer = Settings.default.copy(models = Some(Server))

  test("the door's address is on the platform container while the installation has a server") {
    for spec <- Vector(base, local) do
      val env = variablesByContainer(spec, withServer)
      assertEquals(env.values.flatten.count(_ == PlatformVariables.ModelsAddress), 1, env.toString)
      assert(env(Names.deployment("cart")).contains(PlatformVariables.ModelsAddress), env.toString)
  }

  test("the door's address is on the sidecar of a process, never the process's own container") {
    val containers = deploymentOf(rendered(base.copy(hosting = "process"), withServer))
      .getOrElse(fail("no Deployment rendered"))
      .getSpec
      .getTemplate
      .getSpec
      .getContainers
      .asScala
      .toVector
    assert(containers.size >= 2, containers.map(_.getName).toString)
    val holding =
      containers.filter(_.getEnv.asScala.exists(_.getName == PlatformVariables.ModelsAddress))
    assertEquals(holding.map(_.getImage), Vector(withServer.sidecarImage))
  }

  test("a web-hosted service is given no address: its proxy calls no model") {
    val env = variablesByContainer(base.copy(hosting = "web", port = Some(8080)), withServer)
    assert(!env.values.flatten.exists(_ == PlatformVariables.ModelsAddress), env.toString)
  }

  test("an installation with no model server renders no address") {
    val env = variablesByContainer(local, Settings.default)
    assert(!env.values.flatten.exists(_ == PlatformVariables.ModelsAddress), env.toString)
  }

  test("a service held back for its models gets no Deployment") {
    assertEquals(
      deploymentOf(
        rendered(local, withServer, Some("installation model \"x\" is still being fetched"))
      ),
      None
    )
    assert(deploymentOf(rendered(local, withServer)).isDefined)
  }
