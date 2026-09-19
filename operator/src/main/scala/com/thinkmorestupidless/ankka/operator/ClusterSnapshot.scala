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

  def of(pod: Pod): Option[PodProblem] =
    for
      status   <- Option(pod.getStatus)
      statuses <- Option(status.getContainerStatuses).map(_.asScala.toVector)
      container <- statuses.find(cs =>
        Option(cs.getState).flatMap(s => Option(s.getWaiting)).exists { waiting =>
          Actionable.contains(Option(waiting.getReason).getOrElse(""))
        }
      )
      waiting = container.getState.getWaiting
    yield PodProblem(
      pod = Option(pod.getMetadata).map(_.getName).getOrElse(""),
      reason = Option(waiting.getReason).getOrElse(""),
      message = Option(waiting.getMessage).getOrElse("")
    )

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
