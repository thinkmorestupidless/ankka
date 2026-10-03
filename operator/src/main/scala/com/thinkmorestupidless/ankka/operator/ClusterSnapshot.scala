package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apps.Deployment

import scala.jdk.CollectionConverters.*

/** One Deployment condition, reduced to the three things classification cares about. */
final case class ConditionState(status: Boolean, reason: String, message: String)

/**
 * Why a pod is not running, in the words the cluster used.
 *
 * Carries a container's *waiting* reason, which is where the four failures an operator can actually
 * act on show up: a wrong image tag, a missing registry credential, a missing secret, and an image
 * that starts and exits.
 */
final case class PodProblem(pod: String, reason: String, message: String):
  def describe: String = if message.isEmpty then reason else s"$reason: $message"

object PodProblem:

  /** Reasons worth reporting. Anything else is transient and resolves on its own. */
  val Actionable: Set[String] =
    Set("ImagePullBackOff", "ErrImagePull", "CreateContainerConfigError", "CrashLoopBackOff")

  /**
   * Event reasons that explain a pod no container state does: a failed readiness probe — which is
   * how an image that predates mutual TLS shows, since it opens no probe port — and a volume that
   * cannot be mounted, which is how a certificate cert-manager has not issued yet shows.
   */
  val FromEvents: Set[String] = Set("Unhealthy", "FailedMount")

  /** Every container reports ready. */
  def isReady(pod: Pod): Boolean =
    Option(pod.getStatus)
      .flatMap(s => Option(s.getContainerStatuses))
      .map(_.asScala.toVector)
      .exists(cs => cs.nonEmpty && cs.forall(c => Boolean.box(true) == c.getReady))

  /**
   * The first actionable problem: an init container's first, since nothing else starts until they
   * have — a wasm service's module image that cannot be pulled, or that exits non-zero rather than
   * copying its module, is the whole story of that pod.
   */
  def of(pod: Pod): Option[PodProblem] =
    val name = Option(pod.getMetadata).map(_.getName).getOrElse("")
    Option(pod.getStatus).flatMap { status =>
      def list(xs: java.util.List[io.fabric8.kubernetes.api.model.ContainerStatus]) =
        Option(xs).map(_.asScala.toVector).getOrElse(Vector.empty)
      val init       = list(status.getInitContainerStatuses)
      val containers = list(status.getContainerStatuses)
      initFailure(name, init)
        .orElse(waiting(name, init))
        .orElse(waiting(name, containers))
        .orElse(exited(name, containers))
        .orElse(restarted(name, containers))
    }

  /**
   * A container waiting for an actionable reason. For one that keeps exiting, the message is what
   * it said as it last exited, when it said anything — which is where a refusal names itself —
   * rather than the kubelet's "back-off restarting" line.
   */
  private def waiting(
      pod: String,
      statuses: Vector[io.fabric8.kubernetes.api.model.ContainerStatus]
  ): Option[PodProblem] =
    statuses.iterator
      .flatMap { cs =>
        Option(cs.getState)
          .flatMap(s => Option(s.getWaiting))
          .filter(w => Actionable.contains(Option(w.getReason).getOrElse("")))
          .map { w =>
            val lastWords = Option(cs.getLastState)
              .flatMap(s => Option(s.getTerminated))
              .flatMap(t => Option(t.getMessage))
              .map(_.trim)
              .filter(_.nonEmpty)
            PodProblem(
              pod,
              Option(w.getReason).getOrElse(""),
              lastWords.getOrElse(Option(w.getMessage).getOrElse(""))
            )
          }
      }
      .nextOption()

  /**
   * A container that has just exited non-zero, between the kubelet's restarts: neither waiting in
   * back-off yet nor running, it is otherwise invisible here, and a failed readiness probe would be
   * all the service reported. What it said as it exited is the reason — which is where a runtime
   * that refused its module names why.
   */
  private def exited(
      pod: String,
      statuses: Vector[io.fabric8.kubernetes.api.model.ContainerStatus]
  ): Option[PodProblem] =
    statuses.iterator
      .flatMap { cs =>
        Option(cs.getState)
          .flatMap(s => Option(s.getTerminated))
          .filter(t => Option(t.getExitCode).exists(_ != 0))
          .map { t =>
            val said = Option(t.getMessage).map(_.trim).filter(_.nonEmpty)
            PodProblem(
              pod,
              Option(t.getReason).filter(_.nonEmpty).getOrElse("Error"),
              said.getOrElse(s"container '${cs.getName}' exited ${t.getExitCode}")
            )
          }
      }
      .nextOption()

  /**
   * A container running again after exiting non-zero, and not yet ready: what it said as it last
   * exited. A process that refuses to start spends most of each restart booting towards that
   * refusal, and in that window no state but the last one explains it — without this, the kubelet's
   * failed readiness probe was all the service reported until the next exit, so the reason came and
   * went. Only when it said something: a bare exit code explains less than the probe's own words.
   */
  private def restarted(
      pod: String,
      statuses: Vector[io.fabric8.kubernetes.api.model.ContainerStatus]
  ): Option[PodProblem] =
    statuses.iterator
      .filter(cs => Boolean.box(true) != cs.getReady)
      .filter(cs => Option(cs.getState).flatMap(s => Option(s.getRunning)).isDefined)
      .flatMap { cs =>
        Option(cs.getLastState)
          .flatMap(s => Option(s.getTerminated))
          .filter(t => Option(t.getExitCode).exists(_ != 0))
          .flatMap(t =>
            Option(t.getMessage)
              .map(_.trim)
              .filter(_.nonEmpty)
              .map(said =>
                PodProblem(pod, Option(t.getReason).filter(_.nonEmpty).getOrElse("Error"), said)
              )
          )
      }
      .nextOption()

  /** An init container that ran and failed, now or the last time it ran. */
  private def initFailure(
      pod: String,
      statuses: Vector[io.fabric8.kubernetes.api.model.ContainerStatus]
  ): Option[PodProblem] =
    statuses.iterator
      .flatMap { cs =>
        val terminated =
          Option(cs.getState)
            .flatMap(s => Option(s.getTerminated))
            .orElse(
              Option(cs.getLastState).flatMap(s => Option(s.getTerminated))
            )
        terminated.filter(t => Option(t.getExitCode).exists(_ != 0)).map { t =>
          val why = Option(t.getMessage).orElse(Option(t.getReason)).getOrElse("")
          PodProblem(
            pod,
            "InitContainerFailed",
            s"init container '${cs.getName}' exited ${t.getExitCode}" + (if why.isEmpty then ""
                                                                         else s": $why")
          )
        }
      }
      .nextOption()

/**
 * What one read of the cluster saw for one service.
 *
 * The only input to lifecycle classification, which is what makes every rule in
 * `contracts/observation-rules.md` a unit test with no cluster.
 *
 * Note the two generations. `ankkaGeneration` is the annotation the operator wrote, carrying
 * ankka's own number. `k8sGeneration` is the API server's, and is only meaningful against
 * `observedGeneration` — it says whether the Deployment controller has caught up with the spec.
 * Conflating them silently reports the right answer about the wrong generation.
 */
final case class ClusterSnapshot(
    exists: Boolean,
    ankkaGeneration: Option[Long],
    specReplicas: Int,
    readyReplicas: Int,
    updatedReplicas: Int,
    /**
     * Every pod the Deployment currently owns, old template included. `readyReplicas` counts old
     * pods too, so during a rollout "all ready" can be true of the *previous* generation;
     * `totalReplicas > updatedReplicas` is how you tell.
     */
    totalReplicas: Int,
    k8sGeneration: Option[Long],
    observedGeneration: Option[Long],
    progressing: Option[ConditionState],
    available: Option[ConditionState],
    podProblems: Vector[PodProblem] = Vector.empty
):
  /** True while the API server has not yet acted on the latest spec. */
  def rolloutPending: Boolean =
    (k8sGeneration, observedGeneration) match
      case (Some(spec), Some(observed)) => observed < spec
      case _                            => false

  def progressDeadlineExceeded: Boolean =
    progressing.exists(c => !c.status && c.reason == "ProgressDeadlineExceeded")

  def firstProblem: Option[PodProblem] = podProblems.headOption

object ClusterSnapshot:

  val absent: ClusterSnapshot =
    ClusterSnapshot(
      exists = false,
      ankkaGeneration = None,
      specReplicas = 0,
      readyReplicas = 0,
      updatedReplicas = 0,
      totalReplicas = 0,
      k8sGeneration = None,
      observedGeneration = None,
      progressing = None,
      available = None
    )

  def of(deployment: Deployment): ClusterSnapshot =
    val meta   = Option(deployment.getMetadata)
    val spec   = Option(deployment.getSpec)
    val status = Option(deployment.getStatus)

    def condition(name: String): Option[ConditionState] =
      status
        .flatMap(s => Option(s.getConditions))
        .map(_.asScala.toVector)
        .flatMap(_.find(_.getType == name))
        .map(c =>
          ConditionState(
            status = c.getStatus == "True",
            reason = Option(c.getReason).getOrElse(""),
            message = Option(c.getMessage).getOrElse("")
          )
        )

    ClusterSnapshot(
      exists = true,
      ankkaGeneration = meta
        .flatMap(m => Option(m.getAnnotations))
        .flatMap(a => Option(a.get(Labels.GenerationKey)))
        .flatMap(_.toLongOption),
      // From the live Deployment, not from the descriptor: this is what the cluster is
      // aiming for right now.
      specReplicas = spec.flatMap(s => Option(s.getReplicas)).map(_.intValue).getOrElse(0),
      readyReplicas = status.flatMap(s => Option(s.getReadyReplicas)).map(_.intValue).getOrElse(0),
      updatedReplicas =
        status.flatMap(s => Option(s.getUpdatedReplicas)).map(_.intValue).getOrElse(0),
      totalReplicas = status.flatMap(s => Option(s.getReplicas)).map(_.intValue).getOrElse(0),
      k8sGeneration = meta.flatMap(m => Option(m.getGeneration)).map(_.longValue),
      observedGeneration = status.flatMap(s => Option(s.getObservedGeneration)).map(_.longValue),
      progressing = condition("Progressing"),
      available = condition("Available")
    )

/**
 * The gateway's verdict on a service's route: the `Accepted` and `ResolvedRefs` conditions of the
 * HTTPRoute's parent status for the platform's Gateway, each as (status, reason). A route can be
 * accepted by the listener and still not resolve its backend — the first spike saw exactly that
 * (`RefNotPermitted`, served as a 500) — so both are read.
 */
final case class RouteView(
    accepted: Option[(Boolean, String)],
    resolvedRefs: Option[(Boolean, String)]
)
