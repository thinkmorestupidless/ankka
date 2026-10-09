package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.crd.{
  AnkkaService,
  AnkkaServiceSpec,
  Buckets,
  CloudResource,
  ObjectStorageStatus
}

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

  /**
   * The bucket exists and a key named for the service is allowed on it. `cloud` is the cloud
   * provider's answer when the bucket is in the installation's cloud account (feature 044), and
   * absent on the installation's own store, whose every render is what it was before.
   */
  case Ready(recovered: Boolean, cloud: Option[CloudBucket] = None)

  case Failed(problems: Vector[String])

  def reportedPhase: Option[String] = this match
    case NotAsked        => None
    case Supplied        => Some("Supplied")
    case Waiting(_)      => Some("Waiting")
    case Ready(true, _)  => Some("Recovered")
    case Ready(false, _) => Some("Provisioned")
    case Failed(_)       => Some("Failed")

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

/**
 * A bucket the installation's cloud provider made (feature 044): its name and where a client
 * reaches it, as the provider answered, and the generation of the credential now in its Secret.
 */
final case class CloudBucket(
    bucket: String,
    endpoint: String,
    region: String,
    credentialGeneration: Long
)

/**
 * What the cloud provider answered for the three requests a cloud bucket takes: the service's cloud
 * identity, the bucket, and a credential for the one on the other. The credential's is absent until
 * the identity is answered, since its request names the identity it was given.
 */
final case class CloudBucketPlans(
    identity: CloudPlan,
    bucket: CloudPlan,
    credential: Option[CloudPlan]
)

object ObjectStorage:

  val NoStore: String = "the installation has no object store"

  /**
   * Whether the descriptor gives an object store of its own, by the one declaration of the prefix.
   */
  def supplies(spec: AnkkaServiceSpec): Boolean =
    spec.env.exists(e => PlatformVariables.objectStorage(e.name))

  /**
   * Whether this service's bucket is the installation's cloud account's (feature 044): it asks for
   * one, the installation names a cloud provider, and has no store of its own. An installation with
   * both keeps its own store; moving buckets into the cloud is feature 039's.
   */
  def takesCloudPath(spec: AnkkaServiceSpec, settings: Settings): Boolean =
    spec.provisionObjectStorage && settings.objectStore.isEmpty && settings.cloud.isDefined

  /**
   * Why the service's Deployment is not applied on this pass, if it is not: a cloud bucket still
   * waiting on its provider, whose endpoint and region are the provider's to say, so there is
   * nothing true to render yet. The one function `Rendering` and `ServiceReconciler` both ask.
   */
  def withheld(
      plan: ObjectStoragePlan,
      spec: AnkkaServiceSpec,
      settings: Settings
  ): Option[String] =
    plan match
      case ObjectStoragePlan.Waiting(detail) if takesCloudPath(spec, settings) =>
        Some(detail.getOrElse(WaitingOnProvider))
      case _ => None

  val WaitingOnProvider: String = "waiting on the cloud provider for the bucket"

  /**
   * The cloud requests a service's bucket takes (feature 044): a cloud identity for its
   * ServiceAccount and a bucket at once, and a credential by which the one reaches the other once
   * both are answered, since the credential's request names each by what the provider made.
   */
  def cloudRequests(
      resource: AnkkaService,
      cloud: CloudSettings,
      identity: Option[String],
      bucket: Option[String]
  ): Vector[CloudResource] =
    val spec = resource.getSpec
    val by   = CloudRequests.Requester.of(resource)
    Vector(
      CloudRequests.identity(cloud, by, Names.serviceAccount(spec.serviceName)),
      CloudRequests.bucket(
        cloud,
        by,
        CloudRequests.Purpose.Service,
        cloud.location,
        CloudRequests.BucketAsk()
      )
    ) ++ (for
      i <- identity
      b <- bucket
    yield CloudRequests.bucketCredential(
      cloud,
      by,
      CloudRequests.Purpose.Service,
      b,
      i,
      Buckets.secret(spec.serviceName),
      spec.storageCredentialGeneration.getOrElse(1L)
    ))

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
      observed: ObjectStorageObservation,
      cloud: Option[CloudBucketPlans] = None
  ): ObjectStoragePlan =
    if !spec.provisionObjectStorage then
      if supplies(spec) then ObjectStoragePlan.Supplied else ObjectStoragePlan.NotAsked
    else if takesCloudPath(spec, settings) then cloud.fold(ObjectStoragePlan.Waiting(None))(fold)
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

  /**
   * The three answers, as one plan: the first refusal is the bucket's, whatever answered it; all
   * three ready is a bucket; anything else waits, saying the first thing any of them said.
   */
  private def fold(plans: CloudBucketPlans): ObjectStoragePlan =
    val all = Vector(plans.identity, plans.bucket) ++ plans.credential
    all.collectFirst { case CloudPlan.Failed(detail) => detail } match
      case Some(detail) => ObjectStoragePlan.Failed(Vector(detail))
      case None =>
        (plans.identity, plans.bucket, plans.credential) match
          case (_: CloudPlan.Ready, CloudPlan.Ready(o, recovered, _), Some(c: CloudPlan.Ready)) =>
            val found = for
              bucket   <- o.get(CloudRequests.Keys.Bucket)
              endpoint <- o.get(CloudRequests.Keys.Endpoint)
              region   <- o.get(CloudRequests.Keys.Region)
            yield CloudBucket(bucket, endpoint, region, c.credentialGeneration.getOrElse(1L))
            found match
              case Some(b) => ObjectStoragePlan.Ready(recovered, Some(b))
              case None =>
                ObjectStoragePlan.Failed(
                  Vector(
                    "the cloud provider answered the bucket without its bucket, endpoint and region"
                  )
                )
          case _ => ObjectStoragePlan.Waiting(all.flatMap(_.said).headOption)

  /** The bucket's address on the internet, when its descriptor asks and there is a base domain. */
  def publicAddress(spec: AnkkaServiceSpec, settings: Settings): Option[String] =
    for
      _ <- Option.when(spec.provisionObjectStorage && spec.exposeObjectStorage)(())
      // A cloud bucket is reached at the cloud's address, not through the installation's gateway.
      _    <- Option.when(!takesCloudPath(spec, settings))(())
      base <- settings.baseDomain
    yield Buckets.publicAddress(spec.projectId, spec.serviceName, base, settings.httpsPort)

  def status(
      plan: ObjectStoragePlan,
      spec: AnkkaServiceSpec,
      settings: Settings
  ): Option[ObjectStorageStatus] =
    // A cloud bucket's name is the provider's, known once it has answered; the store's is derived.
    val bucket = plan match
      case ObjectStoragePlan.Ready(_, Some(cloud)) => cloud.bucket
      case _ if takesCloudPath(spec, settings)     => ""
      case _                                       => Buckets.name(spec.projectId, spec.serviceName)
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
        case ObjectStoragePlan.Ready(recovered, _) =>
          ObjectStorageStatus(
            phase = phase,
            bucket = bucket,
            publicAddress = publicAddress(spec, settings),
            recovered = recovered
          )
        case ObjectStoragePlan.NotAsked => ObjectStorageStatus(phase = phase)
    }
