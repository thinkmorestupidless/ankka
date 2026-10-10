package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job

import scala.jdk.CollectionConverters.*

/**
 * The mover's Job, as the operator renders it for one phase of a move (feature 039,
 * contracts/operator.md "The Job"): the service's two storage credentials by reference and no
 * other, owned by the service, never retried, and gone a day after it finishes.
 */
class MoveJobRenderingSuite extends munit.FunSuite:

  private val settings = Settings.default.copy(
    objectStore = Some(
      ObjectStoreSettings(
        "http://garage.garage-system.svc.cluster.local:3903",
        "token",
        "http://garage.garage-system.svc.cluster.local:3900",
        "garage",
        ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
      )
    ),
    gcs = Some(GcsSettings("ankka", 7)),
    storageMoverImage = "ankka-storage-mover:9.9.9"
  )

  private val spec = AnkkaServiceSpec(
    projectId = "casino",
    serviceName = "kyc",
    image = "kyc:1",
    provisionObjectStorage = true
  )

  private val resource =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-casino").withName("kyc").withUid("u-1").build()
    )
    r.setSpec(spec)
    r

  private def job(phase: MovePhase, deadline: Option[Long] = None): Job =
    Rendering.moveJob(
      resource,
      spec,
      "ankka-casino",
      settings,
      2,
      phase,
      // The target bucket as its cloud provider answered it: where it is reached is the answer's.
      CloudBucket("t-casino-kyc-1", "https://storage.googleapis.com", "auto", 1L),
      deadline
    )

  private def env(j: Job): Map[String, String] =
    container(j).getEnv.asScala.map(e => e.getName -> Option(e.getValue).getOrElse("")).toMap

  private def secretRefs(j: Job): Map[String, (String, String)] =
    container(j).getEnv.asScala
      .flatMap(e =>
        Option(e.getValueFrom)
          .flatMap(v => Option(v.getSecretKeyRef))
          .map(r => e.getName -> (r.getName, r.getKey))
      )
      .toMap

  private def container(j: Job) = j.getSpec.getTemplate.getSpec.getContainers.asScala.head

  test(
    "a phase's Job is named for the service, the move and the phase, in the project's namespace"
  ) {
    assertEquals(job(MovePhase.Copy).getMetadata.getName, "kyc-move-2-copy")
    assertEquals(job(MovePhase.Verify).getMetadata.getName, "kyc-move-2-verify")
    assertEquals(job(MovePhase.Copy).getMetadata.getNamespace, "ankka-casino")
  }

  test("it is owned by the service, never retried, and removed a day after it finishes") {
    val j = job(MovePhase.Copy)
    assertEquals(j.getMetadata.getOwnerReferences.asScala.map(_.getUid).toVector, Vector("u-1"))
    assertEquals(j.getSpec.getBackoffLimit.intValue, 0)
    assertEquals(j.getSpec.getTtlSecondsAfterFinished.intValue, 86400)
    assertEquals(j.getSpec.getTemplate.getSpec.getRestartPolicy, "Never")
  }

  test("the copy has no deadline; the verify stops at what remains of the write pause bound") {
    assertEquals(Option(job(MovePhase.Copy).getSpec.getActiveDeadlineSeconds), None)
    assertEquals(
      job(MovePhase.Verify, Some(540L)).getSpec.getActiveDeadlineSeconds.longValue,
      540L
    )
  }

  test("it holds the service's two storage credentials, by reference, and no other") {
    assertEquals(
      secretRefs(job(MovePhase.Copy)),
      Map(
        "MOVER_SOURCE_ACCESS_KEY" -> ("kyc-storage", "ANKKA_S3_ACCESS_KEY"),
        "MOVER_SOURCE_SECRET_KEY" -> ("kyc-storage", "ANKKA_S3_SECRET_KEY"),
        "MOVER_TARGET_ACCESS_KEY" -> ("kyc-cloud-storage", "ANKKA_S3_ACCESS_KEY"),
        "MOVER_TARGET_SECRET_KEY" -> ("kyc-cloud-storage", "ANKKA_S3_SECRET_KEY")
      )
    )
    assert(Option(container(job(MovePhase.Copy)).getEnvFrom).forall(_.isEmpty))
  }

  test("it is told where each bucket is, and the mode, and runs the mover's image") {
    val c = container(job(MovePhase.Verify))
    assertEquals(c.getImage, "ankka-storage-mover:9.9.9")
    assertEquals(c.getImagePullPolicy, "IfNotPresent")
    assertEquals(c.getArgs.asScala.toVector, Vector("verify"))
    assertEquals(c.getTerminationMessagePolicy, "FallbackToLogsOnError")
    val e = env(job(MovePhase.Verify))
    assertEquals(e("MOVER_SOURCE_ENDPOINT"), "http://garage.garage-system.svc.cluster.local:3900")
    assertEquals(e("MOVER_SOURCE_REGION"), "garage")
    assertEquals(e("MOVER_SOURCE_BUCKET"), "casino.kyc")
    assertEquals(e("MOVER_TARGET_ENDPOINT"), "https://storage.googleapis.com")
    assertEquals(e("MOVER_TARGET_REGION"), "auto")
    assertEquals(e("MOVER_TARGET_BUCKET"), "t-casino-kyc-1")
  }

  test("it runs as the service, so the policies that let the service reach both stores let it") {
    val pod = job(MovePhase.Copy).getSpec.getTemplate
    assertEquals(pod.getSpec.getServiceAccountName, "kyc")
    val labels = pod.getMetadata.getLabels.asScala.toMap
    Labels.identity("casino", "kyc").foreach((k, v) => assertEquals(labels.get(k), Some(v), k))
    assertEquals(labels.get(Labels.RoleKey), Some("storage-mover"))
  }

  // A move's acts as actions (feature 039).

  private val target = CloudBucket("t-casino-kyc-1", "https://storage.googleapis.com", "auto", 1L)
  private val asked  = Vector(new com.thinkmorestupidless.ankka.crd.CloudResource)

  private def acted(acts: StorageMove.Act*): Vector[Action] =
    Rendering.moveActions(
      resource,
      spec,
      "ankka-casino",
      settings,
      2,
      acts.toVector,
      Some(target),
      asked,
      keyGeneration = 3
    )

  test("a move asks for its target, then runs the copy into it") {
    val actions = acted(StorageMove.Act.AskForBucket, StorageMove.Act.Copy(target.bucket))
    assertEquals(actions.count(_.isInstanceOf[Action.EnsureCloudResource]), 1)
    val jobs = actions.collect { case Action.EnsureMoveJob(j) => j.getMetadata.getName }
    assertEquals(jobs, Vector(Names.moveJob("kyc", 2, MovePhase.Copy)))
  }

  test("the write pause is on the key in place, and the verify runs within what is left of it") {
    val actions = acted(StorageMove.Act.PauseWrites, StorageMove.Act.Verify(target.bucket, 120L))
    assert(actions.contains(Action.PauseWrites("casino.kyc", 3)), actions.toString)
    val verify = actions.collectFirst { case Action.EnsureMoveJob(j) => j }.get
    assertEquals(verify.getMetadata.getName, Names.moveJob("kyc", 2, MovePhase.Verify))
    assertEquals(verify.getSpec.getActiveDeadlineSeconds.longValue, 120L)
  }

  test("a failed move gives the key its writes back, and a switch acts on nothing here") {
    assertEquals(acted(StorageMove.Act.ResumeWrites), Vector(Action.ResumeWrites("casino.kyc", 3)))
    assertEquals(acted(StorageMove.Act.Switch(target.bucket)), Vector.empty[Action])
  }

  test("no Job is run before the target bucket is answered") {
    val actions = Rendering.moveActions(
      resource,
      spec,
      "ankka-casino",
      settings,
      2,
      Vector(StorageMove.Act.Copy("x")),
      None,
      Vector.empty,
      keyGeneration = 0
    )
    assertEquals(actions, Vector.empty[Action])
  }
