package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec, EnvEntry}
import io.fabric8.kubernetes.api.model.{
  Container,
  ContainerBuilder,
  ContainerStateBuilder,
  ContainerStateTerminatedBuilder,
  ContainerStateWaitingBuilder,
  ContainerStatusBuilder,
  ObjectMetaBuilder,
  PodBuilder,
  PodStatusBuilder
}
import io.fabric8.kubernetes.api.model.apps.Deployment

import scala.jdk.CollectionConverters.*

/**
 * What the operator renders for `hosting: wasm` (feature 016): one container, the runtime's image,
 * with the module copied in by the service's own image run once as an init container — and the rest
 * of the pod exactly an embedded service's.
 */
class WasmHostingRenderingSuite extends munit.FunSuite:

  private val settings = Settings.default.copy(sidecarImage = "ankka-sidecar:9.9.9")

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-checkout").withName("cart").withUid("u").build()
    )
    r.setSpec(spec)
    r

  private val embedded = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 1L,
    image = "my-cart-module:1.0.0",
    port = Some(9000),
    env = List(
      EnvEntry("GREETING", Some("hi"), None, None),
      EnvEntry("ANTHROPIC_API_KEY", None, Some("models"), Some("anthropic")),
      EnvEntry("ANKKA_DB_HOST", Some("postgres"), None, None)
    )
  )
  private val wasm = embedded.copy(hosting = "wasm")

  private def deployment(
      spec: AnkkaServiceSpec,
      plan: ProvisioningPlan = ProvisioningPlan.Supplied
  ): Deployment =
    Rendering
      .render(resource(spec), settings, plan)
      .toOption
      .get
      .collectFirst { case Action.ApplyDeployment(d) => d }
      .get

  private def pod(spec: AnkkaServiceSpec, plan: ProvisioningPlan = ProvisioningPlan.Supplied) =
    deployment(spec, plan).getSpec.getTemplate.getSpec

  private def envOf(c: Container): Map[String, String] =
    c.getEnv.asScala.map(e => e.getName -> Option(e.getValue).getOrElse("<ref>")).toMap

  test("wasm hosting renders one container, the runtime's image, loading the module") {
    val cs = pod(wasm).getContainers.asScala.toVector
    assertEquals(cs.map(_.getImage), Vector("ankka-sidecar:9.9.9"))
    val env = envOf(cs.head)
    assertEquals(env("ANKKA_WASM_MODULE"), "/ankka/module/service.wasm")
    // Unsplit: one container has one environment, and the runtime withholds the reserved names
    // from the module when it reads them.
    assert(
      env.contains("GREETING") && env.contains("ANTHROPIC_API_KEY") && env.contains("ANKKA_DB_HOST")
    )
    // Nothing of a process: no address to dial, no callback port.
    assert(
      !env.keySet.exists(k => k.startsWith("ANKKA_PROCESS_") || k.startsWith("ANKKA_SIDECAR_")),
      env
    )
    val mount = cs.head.getVolumeMounts.asScala.find(_.getName == "ankka-module").get
    assertEquals((mount.getMountPath, mount.getReadOnly), ("/ankka/module", java.lang.Boolean.TRUE))
  }

  test("a module's runtime holds the secret key; the module's config is answered absent for it") {
    // The runtime withholds it when the module asks (WasmHostSuite); here, that it is there to hold.
    val cs = pod(wasm).getContainers.asScala.toVector
    assertEquals(envOf(cs.head).get("ANKKA_SECRET_KEY"), Some("<ref>"))
  }

  test(
    "the service's image runs once, as an init container, copying the module into a shared volume"
  ) {
    val p    = pod(wasm)
    val init = p.getInitContainers.asScala.toVector
    assertEquals(init.map(_.getImage), Vector("my-cart-module:1.0.0"))
    val module = init.head
    assertEquals(module.getImagePullPolicy, "IfNotPresent")
    assert(
      module.getCommand.isEmpty && module.getArgs.isEmpty,
      "the image's own command does the copy"
    )
    assertEquals(
      module.getVolumeMounts.asScala.map(m => (m.getName, m.getMountPath, m.getReadOnly)).toVector,
      Vector(("ankka-module", "/ankka/module", null))
    )
    assertEquals(module.getResources.getLimits.get("memory").toString, "64Mi")
    val volume = p.getVolumes.asScala.find(_.getName == "ankka-module").get
    assertEquals(volume.getEmptyDir.getSizeLimit.toString, "64Mi")
  }

  test("with a provisioned database, the module is copied before the schema is established") {
    val init =
      pod(wasm, ProvisioningPlan.Ready(recovered = false)).getInitContainers.asScala.toVector
    assertEquals(init.map(_.getImage).head, "my-cart-module:1.0.0")
    assertEquals(init.size, 2)
  }

  test("everything else about the pod is an embedded service's") {
    def withoutModule(c: Container): Container =
      new ContainerBuilder(c)
        .withImage("")
        .withTerminationMessagePolicy(null)
        .withEnv(c.getEnv.asScala.filterNot(_.getName == "ANKKA_WASM_MODULE").asJava)
        .withVolumeMounts(c.getVolumeMounts.asScala.filterNot(_.getName == "ankka-module").asJava)
        .build()
    val a = pod(embedded)
    val b = pod(wasm)
    assertEquals(withoutModule(b.getContainers.get(0)), withoutModule(a.getContainers.get(0)))
    assertEquals(b.getVolumes.asScala.filterNot(_.getName == "ankka-module"), a.getVolumes.asScala)
    assertEquals(b.getServiceAccountName, a.getServiceAccountName)
    val (da, db) = (deployment(embedded), deployment(wasm))
    assertEquals(db.getSpec.getSelector, da.getSpec.getSelector)
    assertEquals(db.getSpec.getStrategy, da.getSpec.getStrategy)
    assertEquals(db.getSpec.getTemplate.getMetadata, da.getSpec.getTemplate.getMetadata)
  }

  test("an embedded service renders no module init container and no module volume") {
    val p = pod(embedded)
    assert(p.getInitContainers.isEmpty)
    assert(!p.getVolumes.asScala.exists(_.getName == "ankka-module"))
  }

  test("wasm hosting with no runtime image configured is refused, not rendered") {
    val refused =
      Rendering.render(resource(wasm), settings.copy(sidecarImage = ""), ProvisioningPlan.Supplied)
    assert(refused.left.exists(_.exists(_.contains("no sidecar image"))), refused)
  }

  test("a pull secret is on the pod, so the module image and the runtime are pulled with it") {
    val p = pod(wasm.copy(imagePullSecret = Some("ankka-registry")))
    assertEquals(p.getImagePullSecrets.asScala.map(_.getName).toVector, Vector("ankka-registry"))
  }

  // ── What the pod reports ───────────────────────────────────────────────────

  private def podWith(init: io.fabric8.kubernetes.api.model.ContainerStatus) =
    new PodBuilder()
      .withMetadata(new ObjectMetaBuilder().withName("cart-0").build())
      .withStatus(new PodStatusBuilder().withInitContainerStatuses(init).build())
      .build()

  private def waiting(reason: String, message: String = "") =
    new ContainerStateBuilder()
      .withWaiting(
        new ContainerStateWaitingBuilder().withReason(reason).withMessage(message).build()
      )
      .build()

  test("a runtime that refuses its module names why, in the reason the service reports") {
    val node = pod(wasm).getContainers.get(0)
    assertEquals(node.getTerminationMessagePolicy, "FallbackToLogsOnError")
    val refused = new ContainerStatusBuilder()
      .withName("cart")
      .withState(waiting("CrashLoopBackOff", "back-off 10s restarting failed container"))
      .withLastState(
        new ContainerStateBuilder()
          .withTerminated(
            new ContainerStateTerminatedBuilder()
              .withExitCode(1)
              .withMessage("refusing to host /ankka/module/service.wasm: ABI version 2")
              .build()
          )
          .build()
      )
      .build()
    val p = new PodBuilder()
      .withMetadata(new ObjectMetaBuilder().withName("cart-0").build())
      .withStatus(new PodStatusBuilder().withContainerStatuses(refused).build())
      .build()
    val problem = PodProblem.of(p).get
    assertEquals(problem.reason, "CrashLoopBackOff")
    assert(problem.message.contains("ABI version 2"), problem.message)
  }

  test("between restarts, a runtime that just exited still names why") {
    val exited = new ContainerStatusBuilder()
      .withName("cart")
      .withState(
        new ContainerStateBuilder()
          .withTerminated(
            new ContainerStateTerminatedBuilder()
              .withExitCode(1)
              .withReason("Error")
              .withMessage("refusing to host /ankka/module/service.wasm: ABI version 2")
              .build()
          )
          .build()
      )
      .build()
    val p = new PodBuilder()
      .withMetadata(new ObjectMetaBuilder().withName("cart-0").build())
      .withStatus(new PodStatusBuilder().withContainerStatuses(exited).build())
      .build()
    val problem = PodProblem.of(p).get
    assertEquals(problem.reason, "Error")
    assert(problem.message.contains("ABI version 2"), problem.message)
  }

  // features/topics/contracts.feature: a refusal reaches `services get` whatever the exit code
  test(
    "a service that refused to start and exited 0 still names why, from its termination message"
  ) {
    val exited = new ContainerStatusBuilder()
      .withName("wallet")
      .withState(
        new ContainerStateBuilder()
          .withTerminated(
            new ContainerStateTerminatedBuilder()
              .withExitCode(0)
              .withReason("Completed")
              .withMessage(
                "cannot start ankka projections:\n  - consumer 'relay' publishes to 'orders' with no contract"
              )
              .build()
          )
          .build()
      )
      .build()
    val p = new PodBuilder()
      .withMetadata(new ObjectMetaBuilder().withName("wallet-0").build())
      .withStatus(new PodStatusBuilder().withContainerStatuses(exited).build())
      .build()
    val problem = PodProblem.of(p).get
    assert(problem.message.contains("with no contract"), problem.message)
    // An exit of 0 with nothing said is not a problem: a container may be replaced quietly.
    val quiet = new ContainerStatusBuilder()
      .withName("wallet")
      .withState(
        new ContainerStateBuilder()
          .withTerminated(new ContainerStateTerminatedBuilder().withExitCode(0).build())
          .build()
      )
      .build()
    val q = new PodBuilder()
      .withMetadata(new ObjectMetaBuilder().withName("wallet-0").build())
      .withStatus(new PodStatusBuilder().withContainerStatuses(quiet).build())
      .build()
    assertEquals(PodProblem.of(q), None)
  }

  /** A container running again after it last exited, ready or not, saying `said` as it exited. */
  private def runningAfterExit(ready: Boolean, said: String) =
    val status = new ContainerStatusBuilder()
      .withName("cart")
      .withReady(ready)
      .withState(
        new ContainerStateBuilder()
          .withRunning(new io.fabric8.kubernetes.api.model.ContainerStateRunningBuilder().build())
          .build()
      )
      .withLastState(
        new ContainerStateBuilder()
          .withTerminated(
            new ContainerStateTerminatedBuilder()
              .withExitCode(1)
              .withReason("Error")
              .withMessage(said)
              .build()
          )
          .build()
      )
      .build()
    new PodBuilder()
      .withMetadata(new ObjectMetaBuilder().withName("cart-0").build())
      .withStatus(new PodStatusBuilder().withContainerStatuses(status).build())
      .build()

  test("booting again after refusing to start, a runtime still names why it last exited") {
    val problem = PodProblem.of(
      runningAfterExit(ready = false, "the service declares gRPC and registers no gRPC endpoint")
    )
    assertEquals(problem.map(_.reason), Some("Error"))
    assert(problem.exists(_.message.contains("registers no gRPC endpoint")), problem.toString)
  }

  test("a ready container is no problem, whatever it said when it last exited") {
    assertEquals(PodProblem.of(runningAfterExit(ready = true, "an old refusal")), None)
  }

  test("a container that last exited saying nothing leaves the kubelet's own words to explain it") {
    assertEquals(PodProblem.of(runningAfterExit(ready = false, "")), None)
  }

  test("a module image that cannot be pulled is the pod's problem") {
    val status = new ContainerStatusBuilder()
      .withName("cart-module")
      .withState(waiting("ImagePullBackOff", "no such image"))
      .build()
    assertEquals(PodProblem.of(podWith(status)).map(_.reason), Some("ImagePullBackOff"))
  }

  test("a module image that exits non-zero, rather than copying its module, is the pod's problem") {
    val exited = new ContainerStateBuilder()
      .withTerminated(
        new ContainerStateTerminatedBuilder().withExitCode(1).withReason("Error").build()
      )
      .build()
    val status = new ContainerStatusBuilder()
      .withName("cart-module")
      .withState(waiting("PodInitializing"))
      .withLastState(exited)
      .build()
    val problem = PodProblem.of(podWith(status)).get
    assertEquals(problem.reason, "InitContainerFailed")
    assert(problem.message.contains("'cart-module' exited 1"), problem.message)
  }
