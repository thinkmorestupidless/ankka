package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.api.ServiceLifecycle
import com.thinkmorestupidless.ankka.controlplane.domain.{
  HostnameReport,
  Service,
  ServiceObservation,
  StorageReport
}
import com.thinkmorestupidless.ankka.crd.AnkkaServiceStatus

/** What the control plane managed to learn about a service this pass. */
enum ClusterView:
  /** The cluster could not be reached, or the watch is not connected. */
  case Unreachable(reason: String)

  /**
   * The platform read the desired state and declined to run it — a descriptor that cannot be
   * projected, a declared runtime outside the supported range. Unlike `Unreachable`, this is a
   * certain answer about the service itself, not a stale reading of the cluster.
   */
  case Refused(reason: String)

  /** The resource exists, and nothing has ever written a status to it. */
  case NoReport

  /** The operator reported. */
  case Reported(status: AnkkaServiceStatus)

/**
 * An operator's report becomes an observation.
 *
 * Total and pure, no clock. The three cases are separate because they mean different things to
 * whoever is reading `services list`, and collapsing any two of them loses the distinction that
 * makes the listing trustworthy:
 *
 *   - unreachable — what was last known, explicitly not confirmed
 *   - no report — the resource exists but nothing is acting on it, which is how an operator
 *     discovers the ankka operator is not installed, is crash-looping, or is watching a different
 *     namespace prefix
 *   - reported — a real reading
 */
object StatusIngest:

  def observe(service: Service, view: ClusterView): ServiceObservation = view match
    case ClusterView.Unreachable(reason) =>
      // Restate what is already recorded, marked unconfirmed. The dedupe in
      // `ServiceEntity.observe` then makes an outage cost one event, not one per sweep.
      ServiceObservation(
        generation = service.generation,
        lifecycle = service.lifecycle,
        readyInstances = service.readyInstances,
        desiredInstances = service.desiredInstances,
        detail = Some(s"could not reach the cluster: $reason"),
        confirmed = false,
        database = service.database,
        broker = service.broker,
        objectStorage = service.objectStorage,
        storage = service.storage,
        hostnames = service.hostnameReports
      )

    case ClusterView.Refused(reason) =>
      ServiceObservation(
        generation = service.generation,
        lifecycle = ServiceLifecycle.Unavailable,
        readyInstances = 0,
        desiredInstances = service.desiredInstances,
        detail = Some(reason),
        confirmed = true,
        database = service.database,
        broker = service.broker,
        objectStorage = service.objectStorage,
        storage = service.storage,
        hostnames = service.hostnameReports
      )

    case ClusterView.NoReport =>
      ServiceObservation(
        generation = service.generation,
        lifecycle = ServiceLifecycle.UpdateInProgress,
        readyInstances = 0,
        desiredInstances = 0,
        detail = Some("no operator has reported on this service"),
        confirmed = false,
        database = service.database,
        broker = service.broker,
        objectStorage = service.objectStorage,
        storage = service.storage,
        hostnames = service.hostnameReports
      )

    case ClusterView.Reported(status) =>
      ServiceObservation(
        // The generation the *operator* stated, not the one the control plane currently
        // holds. A report about a superseded generation is passed through untouched and
        // dropped by the guard in the fold — there is exactly one place staleness is
        // decided, and second-guessing it here would make two.
        generation = status.generation,
        lifecycle =
          ServiceLifecycle.byName(status.lifecycle).getOrElse(ServiceLifecycle.Unavailable),
        readyInstances = status.readyInstances,
        desiredInstances = status.desiredInstances,
        detail = withObjectStorage(withRoute(status.detail, status.route), status.objectStorage),
        confirmed = true,
        database = status.database.map(_.phase),
        broker = status.broker.map(_.phase),
        objectStorage = status.objectStorage.map(_.phase),
        storage = status.objectStorage.flatMap(report),
        // Verbatim, in the spec's order (feature 045): the words are the authority's and the
        // gateway's, and only the operator read them.
        hostnames =
          status.hostnames.toVector.map(h => HostnameReport(h.hostname, h.state, h.reason))
      )

  /**
   * What the operator reported of a bucket beyond its phase (feature 039). Absent for a status
   * written before the feature, which names no store, so a stored observation is not rewritten
   * until an operator that reports more says something new.
   */
  private def report(
      s: com.thinkmorestupidless.ankka.crd.ObjectStorageStatus
  ): Option[StorageReport] =
    Option.when(s.store.nonEmpty || s.move.isDefined)(
      StorageReport(
        store = Option(s.store).filter(_.nonEmpty),
        bucket = Option(s.bucket).filter(_.nonEmpty),
        bucketAddress = s.publicAddress,
        location = s.location,
        softDeleteDays = s.softDeleteDays,
        move = s.move.map(_.state).filter(_.nonEmpty),
        moveGeneration = s.move.map(_.generation),
        movePausedAt = s.move.flatMap(_.pauseStartedAt)
      )
    )

  /**
   * A route the gateway has not accepted — or has accepted and cannot resolve — is said in the
   * detail, so a hostname that will not answer says why on `services get`. An accepted route is the
   * expected state and adds nothing.
   */
  /**
   * Why a bucket waits or failed is said in the detail (feature 034), as a route's state is: "the
   * installation has no object store" is the whole of what a member needs to read, and the status's
   * object storage field carries only the phase.
   */
  private def withObjectStorage(
      detail: Option[String],
      storage: Option[com.thinkmorestupidless.ankka.crd.ObjectStorageStatus]
  ): Option[String] =
    storage.flatMap(_.detail) match
      case None => detail
      case Some(reason) =>
        val phrase = s"object storage: $reason"
        Some(detail.fold(phrase)(d => s"$d; $phrase"))

  private def withRoute(detail: Option[String], route: Option[String]): Option[String] =
    route.filterNot(_ == "accepted") match
      case None => detail
      case Some(state) =>
        val phrase = s"route $state"
        Some(detail.fold(phrase)(d => s"$d; $phrase"))
