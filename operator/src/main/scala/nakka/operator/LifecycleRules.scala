package nakka.operator

import nakka.crd.{NakkaServiceSpec, NakkaServiceStatus}

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * What the cluster is doing, as one of seven words.
 *
 * Total, pure, and clock-free — the timestamp is supplied rather than read — so every rule is a
 * unit test with no cluster and no Docker. The lifecycle is *derived* every pass rather than stored
 * as a state machine, which is what makes it self-correcting: there is no stuck state to get out
 * of, only an input that changed.
 */
object LifecycleRules:

  private val Rfc3339 = DateTimeFormatter.ISO_INSTANT

  /**
   * The rules, in order. First match wins, and two of the orderings are load-bearing.
   *
   *   - **Paused before failed.** Pause is desired state. A paused service whose pod is still
   *     terminating is `Paused`, not `Failed`; reversing them makes pausing a broken service report
   *     the breakage forever.
   *   - **`observedGeneration` before `updatedReplicas`.** `updatedReplicas` is stale until the API
   *     server has acted on the new spec, so checking it first reports `Ready` for the *previous*
   *     generation mid-rollout — the right answer about the wrong generation, which is precisely
   *     what the generation machinery exists to prevent.
   */
  /**
   * One word for the resource's `status.route`, from the gateway's two conditions. Only for an
   * exposed service; the caller leaves the field absent otherwise.
   */
  def routeStatus(exposed: Boolean, baseDomain: Option[String], view: Option[RouteView]): String =
    if !exposed then ""
    else if baseDomain.isEmpty then
      "rejected: the operator has no base domain (NAKKA_BASE_DOMAIN); no route can be rendered"
    else
      view match
        case None => "pending"
        case Some(v) =>
          v.accepted.filter(!_._1).orElse(v.resolvedRefs.filter(!_._1)) match
            case Some((_, reason)) => s"rejected: $reason"
            case None =>
              if v.accepted.exists(_._1) && v.resolvedRefs.exists(_._1) then "accepted"
              else "pending"

  def observe(
      spec: NakkaServiceSpec,
      observed: Option[ClusterSnapshot],
      problems: Vector[String] = Vector.empty,
      metadataGeneration: Long = 0L,
      now: Instant = Instant.EPOCH
  ): NakkaServiceStatus =
    val snapshot = observed.getOrElse(ClusterSnapshot.absent)

    val (lifecycle, ready, desired, detail) =
      if spec.paused then ("Paused", snapshot.readyReplicas, 0, None)
      else if problems.nonEmpty then ("Failed", 0, 0, Some(problems.mkString("; ")))
      else if !snapshot.exists then ("UpdateInProgress", 0, Rendering.replicas(spec), None)
      else if snapshot.progressDeadlineExceeded then
        ("Failed", snapshot.readyReplicas, snapshot.specReplicas, failureDetail(snapshot))
      else if snapshot.rolloutPending then
        ("UpdateInProgress", snapshot.readyReplicas, snapshot.specReplicas, problemDetail(snapshot))
      else if snapshot.updatedReplicas < snapshot.specReplicas then
        ("UpdateInProgress", snapshot.readyReplicas, snapshot.specReplicas, problemDetail(snapshot))
      else if snapshot.totalReplicas > snapshot.updatedReplicas then
        // Pods of the old template are still around. readyReplicas counts them, so without this
        // a rollout reports Ready while the pod being counted ready is an old one — seen live in
        // feature 003, and hidden rather than fixed by Recreate; RollingUpdate brought it back.
        ("UpdateInProgress", snapshot.readyReplicas, snapshot.specReplicas, problemDetail(snapshot))
      else if snapshot.specReplicas > 0 && snapshot.readyReplicas == snapshot.specReplicas then
        ("Ready", snapshot.readyReplicas, snapshot.specReplicas, None)
      else if snapshot.readyReplicas > 0 && snapshot.readyReplicas < snapshot.specReplicas then
        // Written in feature 001 as "unreachable at one replica"; reachable since feature 004.
        ("PartiallyReady", snapshot.readyReplicas, snapshot.specReplicas, problemDetail(snapshot))
      else if snapshot.specReplicas > 0 then
        ("Unavailable", 0, snapshot.specReplicas, problemDetail(snapshot))
      else ("NotDeployed", 0, 0, None)

    NakkaServiceStatus(
      generation = spec.generation,
      observedGeneration = metadataGeneration,
      lifecycle = lifecycle,
      readyInstances = ready,
      desiredInstances = desired,
      detail = detail,
      lastTransitionTime = Rfc3339.format(now)
    )

  /** A resource whose version this operator does not understand. */
  def unsupportedVersion(apiVersion: String, known: String): NakkaServiceStatus =
    NakkaServiceStatus(
      lifecycle = "Failed",
      detail =
        Some(s"unsupported resource version '$apiVersion'; this operator understands '$known'")
    )

  /**
   * Why a rollout gave up.
   *
   * A pod's own reason is more useful than the Deployment condition's — "ImagePullBackOff: manifest
   * unknown" tells an operator what to fix, where "ProgressDeadlineExceeded" only says that
   * something did not finish.
   */
  private def failureDetail(snapshot: ClusterSnapshot): Option[String] =
    problemDetail(snapshot)
      .orElse(snapshot.progressing.map(_.message).filter(_.nonEmpty))
      .orElse(Some("the rollout did not complete within its progress deadline"))

  /**
   * The first actionable pod problem, if any.
   *
   * Only a secret's *name* and *key* can ever appear here, and both come from the descriptor the
   * operator wrote — this reads a pod's container status, never a secret's contents, so what verbs
   * the operator holds on `secrets` is beside the point for this particular value.
   */
  private def problemDetail(snapshot: ClusterSnapshot): Option[String] =
    snapshot.firstProblem.map(_.describe)

  /**
   * `ProvisioningPlan` becomes the status an operator reads. The reported phase comes from the plan
   * itself (`reportedPhase`) — never restated here — so the two can never say something different
   * about the same reconcile pass.
   */
  def databaseStatus(
      plan: ProvisioningPlan,
      clusterName: String,
      serviceName: String
  ): nakka.crd.DatabaseStatus =
    val (name, detail) = plan match
      case ProvisioningPlan.Supplied               => ("", None)
      case ProvisioningPlan.Waiting(_, _, _, _, d) => (serviceName, d)
      case ProvisioningPlan.Ready(_)               => (serviceName, None)
      case ProvisioningPlan.Failed(problems)       => (serviceName, Some(problems.mkString("; ")))

    nakka.crd.DatabaseStatus(
      phase = plan.reportedPhase,
      name = name,
      cluster = if plan == ProvisioningPlan.Supplied then "" else clusterName,
      recovered = plan match
        case ProvisioningPlan.Ready(recovered) => recovered
        case _                                 => false
      ,
      detail = detail
    )
