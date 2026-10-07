package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, Buckets, ObjectStorageStatus}

import java.time.Instant

/**
 * What to do about one service's bucket (feature 034), decided from what the store reports before
 * anything is rendered, as `Provisioning.decide` decides about a database.
 */
enum ObjectStoragePlan:
  /** The service neither asks for a bucket nor has a store of its own. Nothing is reported. */
  case NotAsked

  /** The descriptor gives an `ANKKA_S3_` variable: the service has an object store of its own. */
  case Supplied

  /**
   * The bucket or its key is not there yet, or the store cannot be reached, which `detail` says.
   */
  case Waiting(detail: Option[String])

  /** The bucket exists and a key named for the service is allowed on it. */
  case Ready(recovered: Boolean)

  case Failed(problems: Vector[String])

  def reportedPhase: Option[String] = this match
    case NotAsked     => None
    case Supplied     => Some("Supplied")
    case Waiting(_)   => Some("Waiting")
    case Ready(true)  => Some("Recovered")
    case Ready(false) => Some("Provisioned")
    case Failed(_)    => Some("Failed")

/**
 * What the store said about one bucket, read only for a service that asks and only when the
 * installation has a store.
 *
 * @param unreachable
 *   why the store could not be asked, when it could not
 * @param bucketCreated
 *   when the bucket was made; `None` when it has not been
 * @param keyAllowed
 *   whether a key is allowed on the bucket
 * @param resourceCreatedAt
 *   when this incarnation of the service's resource was created
 */
final case class ObjectStorageObservation(
    unreachable: Option[String] = None,
    bucketCreated: Option[Instant] = None,
    keyAllowed: Boolean = false,
    resourceCreatedAt: Option[Instant] = None
)

object ObjectStorageObservation:
  val empty: ObjectStorageObservation = ObjectStorageObservation()

object ObjectStorage:

  val NoStore: String = "the installation has no object store"

  /**
   * Whether the descriptor gives an object store of its own, by the one declaration of the prefix.
   */
  def supplies(spec: AnkkaServiceSpec): Boolean =
    spec.env.exists(e => PlatformVariables.objectStorage(e.name))

  /** Whether the store is to be asked anything for this service at all. */
  def observes(spec: AnkkaServiceSpec, settings: Settings): Boolean =
    spec.provisionObjectStorage && settings.objectStore.isDefined &&
      Buckets.problems(spec.projectId, spec.serviceName).isEmpty

  /**
   * First match wins. The flag's default is `false`, so the flag alone cannot tell "its own store"
   * from "none": the environment says which, and it is on the resource.
   */
  def decide(
      spec: AnkkaServiceSpec,
      settings: Settings,
      observed: ObjectStorageObservation
  ): ObjectStoragePlan =
    if !spec.provisionObjectStorage then
      if supplies(spec) then ObjectStoragePlan.Supplied else ObjectStoragePlan.NotAsked
    else
      val names = Buckets.problems(spec.projectId, spec.serviceName)
      if names.nonEmpty then ObjectStoragePlan.Failed(names)
      else if settings.objectStore.isEmpty then ObjectStoragePlan.Failed(Vector(NoStore))
      else
        observed.unreachable match
          case Some(reason) => ObjectStoragePlan.Waiting(Some(reason))
          case None =>
            observed.bucketCreated match
              case Some(created) if observed.keyAllowed =>
                ObjectStoragePlan.Ready(
                  recovered = observed.resourceCreatedAt.exists(created.isBefore)
                )
              case _ => ObjectStoragePlan.Waiting(None)

  /** The bucket's address on the internet, when its descriptor asks and there is a base domain. */
  def publicAddress(spec: AnkkaServiceSpec, settings: Settings): Option[String] =
    for
      _    <- Option.when(spec.provisionObjectStorage && spec.exposeObjectStorage)(())
      base <- settings.baseDomain
    yield Buckets.publicAddress(spec.projectId, spec.serviceName, base, settings.httpsPort)

  def status(
      plan: ObjectStoragePlan,
      spec: AnkkaServiceSpec,
      settings: Settings
  ): Option[ObjectStorageStatus] =
    val bucket = Buckets.name(spec.projectId, spec.serviceName)
    plan.reportedPhase.map { phase =>
      plan match
        case ObjectStoragePlan.Supplied => ObjectStorageStatus(phase = phase)
        case ObjectStoragePlan.Failed(problems) =>
          ObjectStorageStatus(
            phase = phase,
            bucket = bucket,
            detail = Some(problems.mkString("; "))
          )
        case ObjectStoragePlan.Waiting(detail) =>
          ObjectStorageStatus(
            phase = phase,
            bucket = bucket,
            publicAddress = publicAddress(spec, settings),
            detail = detail
          )
        case ObjectStoragePlan.Ready(recovered) =>
          ObjectStorageStatus(
            phase = phase,
            bucket = bucket,
            publicAddress = publicAddress(spec, settings),
            recovered = recovered
          )
        case ObjectStoragePlan.NotAsked => ObjectStorageStatus(phase = phase)
    }
