package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.crd.{
  AnkkaService,
  AnkkaServiceSpec,
  BucketSpec,
  BucketStatus,
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
    credentialGeneration: Long,
    /** Where the provider made it, in the installation's words (feature 039). */
    location: String = "",
    /** Where anyone reads a public bucket's objects, as the provider answered (feature 055). */
    publicUrl: Option[String] = None
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

  /** Why a bucket in Garage cannot be had without a storage credential (feature 039). */
  val NoCredential: String = "a bucket in Garage is reached only with a storage credential"

  /**
   * Whether the descriptor gives an object store of its own, by the one declaration of the prefix.
   */
  def supplies(spec: AnkkaServiceSpec): Boolean =
    spec.env.exists(e => PlatformVariables.objectStorage(e.name))

  /** What the operator last reported of a service's bucket, from the resource's status. */
  def reported(resource: AnkkaService): Option[ObjectStorageStatus] =
    Option(resource.getStatus).flatMap(_.objectStorage)

  /**
   * Whether this service's bucket is the installation's cloud account's (features 044 and 039): it
   * asks for one, the installation names a cloud provider, and the bucket's store is the cloud's.
   *
   * The store is where the operator last reported a bucket made (data-model.md, "Where the store of
   * a bucket comes from"): a bucket stays in the store it was made in until a move switches it, and
   * one reported before stores were named was made in Garage, the one store there was. A service
   * with no bucket made yet takes the installation's backend.
   */
  def takesCloudPath(
      spec: AnkkaServiceSpec,
      settings: Settings,
      reported: Option[ObjectStorageStatus]
  ): Boolean =
    val made    = reported.filter(r => r.phase == "Provisioned" || r.phase == "Recovered")
    val inCloud =
      if reported.flatMap(_.move).exists(_.state == "Switched") then true
      else
        made.map(_.store) match
          case Some(store) => store == "gcs"
          case None        => settings.bucketBackend.contains(ObjectStoreBackend.Gcs)
    spec.provisionObjectStorage && settings.cloud.isDefined && inCloud

  /**
   * Why the service's Deployment is not applied on this pass, if it is not: a cloud bucket still
   * waiting on its provider, whose endpoint and region are the provider's to say, so there is
   * nothing true to render yet; or one the provider refused (`refused`). The one function
   * `Rendering` and `ServiceReconciler` both ask.
   */
  def withheld(
      plan: ObjectStoragePlan,
      spec: AnkkaServiceSpec,
      settings: Settings,
      reported: Option[ObjectStorageStatus]
  ): Option[String] =
    plan match
      case ObjectStoragePlan.Waiting(detail) if takesCloudPath(spec, settings, reported) =>
        Some(detail.getOrElse(WaitingOnProvider))
      case _ => refused(plan, spec, settings, reported)

  /**
   * Why the cloud provider refused the service's bucket, if it did. No instance starts: a service
   * that asked for a bucket and runs without one cannot be relied on to run correctly, so it is
   * held back and reported failed with the provider's reason. Instances already running are left as
   * they are; the operator never deletes a Deployment to hold one back.
   */
  def refused(
      plan: ObjectStoragePlan,
      spec: AnkkaServiceSpec,
      settings: Settings,
      reported: Option[ObjectStorageStatus]
  ): Option[String] =
    plan match
      case ObjectStoragePlan.Failed(problems) if takesCloudPath(spec, settings, reported) =>
        Some(problems.mkString("; "))
      case _ => None

  val WaitingOnProvider: String = "waiting on the cloud provider for the bucket"

  /**
   * The cloud requests a service's bucket takes (feature 044): a cloud identity for its
   * ServiceAccount and a bucket at once, and a credential by which the one reaches the other once
   * both are answered, since the credential's request names each by what the provider made.
   *
   * The bucket asks what the descriptor and the installation say of it (feature 039): versions
   * kept, the installation's soft-delete window and name prefix, the descriptor's origins while it
   * is reachable from the internet, and the age at which a noncurrent version goes. It is made in
   * its project's location when the project names one, else the installation's.
   *
   * Once the request exists (`existingBucket`), its location is never rewritten, and its
   * soft-delete window and wrapping key are kept as they were asked: the installation's are taken
   * again only when a member raises the service's `objectStorageSettingsGeneration` above the one
   * the request was stamped with, which it then carries.
   */
  def cloudRequests(
      resource: AnkkaService,
      settings: Settings,
      cloud: CloudSettings,
      projectLocation: Option[String],
      existingBucket: Option[CloudObservation],
      identity: Option[String],
      bucket: Option[String]
  ): Vector[CloudResource] =
    val spec    = resource.getSpec
    val by      = CloudRequests.Requester.of(resource)
    val asked   = existingBucket.flatMap(_.spec).map(_.parameters)
    val stamped = existingBucket
      .flatMap(_.annotations.get(Labels.SettingsGenerationKey))
      .flatMap(_.toIntOption)
      .getOrElse(0)
    val keep = asked.filter(_ => spec.objectStorageSettingsGeneration <= stamped)
    val ask  = CloudRequests.BucketAsk(
      versioning = true,
      softDeleteDays = keep
        .flatMap(_.get(CloudRequests.Keys.SoftDeleteDays))
        .flatMap(_.toIntOption)
        .getOrElse(settings.gcs.fold(GcsSettings.DefaultSoftDeleteDays)(_.softDeleteDays)),
      corsOrigins =
        if spec.exposeObjectStorage then spec.objectStorageOrigins.toVector else Vector.empty,
      namePrefix = settings.gcs.fold("")(_.prefix),
      noncurrentVersionDays = spec.objectStorageVersionAgeDays,
      kmsKey = keep.map(_.getOrElse(CloudRequests.Keys.KmsKey, ""))
    )
    val location = asked
      .flatMap(_.get(CloudRequests.Keys.Location))
      .filter(_.nonEmpty)
      .getOrElse(projectLocation.getOrElse(cloud.location))
    val generation = if keep.isDefined then stamped else spec.objectStorageSettingsGeneration
    Vector(
      CloudRequests.identity(cloud, by, Names.serviceAccount(spec.serviceName)),
      CloudRequests.bucket(
        cloud,
        by,
        CloudRequests.Purpose.Service,
        location,
        ask,
        annotations = Map(Labels.SettingsGenerationKey -> generation.toString)
      )
    ) ++ (for
      // A service that declines a credential reaches its bucket as its cloud identity alone.
      _ <- Option.when(spec.objectStorageCredential)(())
      i <- identity
      b <- bucket
    yield cloudCredential(resource, cloud, i, Vector(b)))

  /**
   * The one credential a service's buckets in the cloud share (features 044 and 055): its cloud
   * identity on every bucket given, written into `<service>-cloud-storage`, at the member's count
   * of credentials issued again plus one, since a provider's generations start at 1.
   */
  def cloudCredential(
      resource: AnkkaService,
      cloud: CloudSettings,
      identity: String,
      buckets: Vector[String]
  ): CloudResource =
    val spec = resource.getSpec
    CloudRequests.bucketCredential(
      cloud,
      CloudRequests.Requester.of(resource),
      CloudRequests.Purpose.Service,
      buckets,
      identity,
      Buckets.cloudSecret(spec.serviceName),
      spec.storageCredentialGeneration.toLong + 1
    )

  /**
   * A named bucket's request (feature 055): what the unnamed one's asks, in the same location and
   * under the same rules for what is kept once asked, with the bucket's name and whether it is
   * public; a signed bucket asks for the descriptor's origins.
   */
  def namedCloudRequest(
      resource: AnkkaService,
      settings: Settings,
      cloud: CloudSettings,
      projectLocation: Option[String],
      bucket: BucketSpec,
      existing: Option[CloudObservation]
  ): CloudResource =
    val spec    = resource.getSpec
    val asked   = existing.flatMap(_.spec).map(_.parameters)
    val stamped = existing
      .flatMap(_.annotations.get(Labels.SettingsGenerationKey))
      .flatMap(_.toIntOption)
      .getOrElse(0)
    val keep = asked.filter(_ => spec.objectStorageSettingsGeneration <= stamped)
    val ask  = CloudRequests.BucketAsk(
      versioning = true,
      softDeleteDays = keep
        .flatMap(_.get(CloudRequests.Keys.SoftDeleteDays))
        .flatMap(_.toIntOption)
        .getOrElse(settings.gcs.fold(GcsSettings.DefaultSoftDeleteDays)(_.softDeleteDays)),
      corsOrigins = if bucket.exposure == "signed" then bucket.origins.toVector else Vector.empty,
      namePrefix = settings.gcs.fold("")(_.prefix),
      noncurrentVersionDays = spec.objectStorageVersionAgeDays,
      kmsKey = keep.map(_.getOrElse(CloudRequests.Keys.KmsKey, ""))
    )
    val location = asked
      .flatMap(_.get(CloudRequests.Keys.Location))
      .filter(_.nonEmpty)
      .getOrElse(projectLocation.getOrElse(cloud.location))
    CloudRequests.bucket(
      cloud,
      CloudRequests.Requester.of(resource),
      CloudRequests.Purpose.Service,
      location,
      ask,
      annotations = Map(
        Labels.SettingsGenerationKey ->
          (if keep.isDefined then stamped else spec.objectStorageSettingsGeneration).toString
      ),
      named = Some(CloudRequests.NamedAsk(bucket.name, bucket.exposure == "public"))
    )

  /**
   * What a move's target bucket has been answered (feature 039), as the move reads it: the bucket
   * as the cloud provider made it once its identity, bucket and credential are all answered.
   */
  def moveRequests(
      plans: CloudBucketPlans,
      named: Vector[CloudBucketPlans] = Vector.empty
  ): (StorageMove.Requests, Vector[CloudBucket]) =
    // The unnamed bucket's target first, then each named one's (feature 055): ready only when all
    // are, refused when any is, else waiting on the first that says why.
    val folded = (plans +: named).map(fold(_, credentialAsked = true))
    val made   = folded.collect { case ObjectStoragePlan.Ready(_, Some(bucket)) => bucket }
    folded.collectFirst { case ObjectStoragePlan.Failed(problems) => problems } match
      case Some(problems) => StorageMove.Requests.Failed(problems.mkString("; ")) -> Vector.empty
      case None if made.size == folded.size =>
        StorageMove.Requests.Ready(made.map(_.bucket)) -> made
      case None =>
        val said = folded.collectFirst { case ObjectStoragePlan.Waiting(Some(d)) => d }
        StorageMove.Requests.Waiting(said) -> Vector.empty

  /**
   * The named buckets a move takes (feature 055, R11): every bucket the descriptor names that the
   * platform made in Garage. One named during the move is not made, and waits for it.
   */
  def movedNamed(
      spec: AnkkaServiceSpec,
      reported: Option[ObjectStorageStatus]
  ): Vector[BucketSpec] =
    val last = reported.toList.flatMap(_.buckets).map(b => b.name -> b).toMap
    spec.buckets.toVector.filter(b =>
      b.made && last
        .get(b.name)
        .exists(r => (r.phase == "Provisioned" || r.phase == "Recovered") && r.store == "garage")
    )

  /**
   * The annotations the cloud provider's answer for the service's cloud identity says its
   * ServiceAccount carries (feature 039): how the cloud binds a workload to that identity, in the
   * provider's words, as `key=value` pairs. None until the identity is answered.
   */
  def serviceAccountAnnotations(plans: CloudBucketPlans): Map[String, String] =
    identityAnnotations(plans.identity)

  /**
   * What the provider's answer to an identity request says to put on the ServiceAccount, whichever
   * of a bucket or the secret store asked for it (features 039 and 038).
   */
  def identityAnnotations(identity: CloudPlan): Map[String, String] =
    identity match
      case CloudPlan.Ready(outputs, _, _, _) =>
        outputs
          .get(CloudRequests.Keys.ServiceAccountAnnotations)
          .toVector
          .flatMap(_.split(','))
          .flatMap(_.split("=", 2) match
            case Array(k, v) if k.trim.nonEmpty => Some(k.trim -> v.trim)
            case _                              => None)
          .toMap
      case _ => Map.empty

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
      reported: Option[ObjectStorageStatus],
      cloud: Option[CloudBucketPlans] = None
  ): ObjectStoragePlan =
    if !spec.provisionObjectStorage then
      if supplies(spec) then ObjectStoragePlan.Supplied else ObjectStoragePlan.NotAsked
    else if takesCloudPath(spec, settings, reported) then
      cloud.fold(ObjectStoragePlan.Waiting(None))(fold(_, spec.objectStorageCredential))
    else if !spec.objectStorageCredential then ObjectStoragePlan.Failed(Vector(NoCredential))
    else
      val names = Buckets.problems(spec.projectId, spec.serviceName)
      if names.nonEmpty then ObjectStoragePlan.Failed(names)
      else if settings.objectStore.isEmpty then ObjectStoragePlan.Failed(Vector(NoStore))
      else
        observed.unreachable match
          case Some(reason) => ObjectStoragePlan.Waiting(Some(reason))
          case None         =>
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
  private def fold(plans: CloudBucketPlans, credentialAsked: Boolean): ObjectStoragePlan =
    val all = Vector(plans.identity, plans.bucket) ++ plans.credential
    all.collectFirst { case CloudPlan.Failed(detail) => detail } match
      case Some(detail) => ObjectStoragePlan.Failed(Vector(detail))
      case None         =>
        // The credential's generation, once it is answered; a service that declined one needs
        // none, and its bucket is ready with the identity and the bucket answered.
        val credential: Option[Long] = plans.credential match
          case Some(c: CloudPlan.Ready) => Some(c.credentialGeneration.getOrElse(1L))
          case _                        => Option.when(!credentialAsked)(0L)
        (plans.identity, plans.bucket, credential) match
          case (_: CloudPlan.Ready, CloudPlan.Ready(o, recovered, _, location), Some(generation)) =>
            val found = for
              bucket   <- o.get(CloudRequests.Keys.Bucket)
              endpoint <- o.get(CloudRequests.Keys.Endpoint)
              region   <- o.get(CloudRequests.Keys.Region)
            yield CloudBucket(
              bucket,
              endpoint,
              region,
              generation,
              location,
              o.get(CloudRequests.Keys.PublicUrl).filter(_.nonEmpty)
            )
            found match
              case Some(b) => ObjectStoragePlan.Ready(recovered, Some(b))
              case None    =>
                ObjectStoragePlan.Failed(
                  Vector(
                    "the cloud provider answered the bucket without its bucket, endpoint and region"
                  )
                )
          case _ => ObjectStoragePlan.Waiting(all.flatMap(_.said).headOption)

  /**
   * One named bucket, decided (feature 055): its name in the store, the plan, and what the status
   * says of it. A supplied bucket's plan is `Supplied` and its name is the descriptor's.
   */
  final case class NamedBucket(
      spec: BucketSpec,
      named: String,
      plan: ObjectStoragePlan,
      status: BucketStatus
  ):
    def made: Boolean = spec.made

    /**
     * Whether the platform makes it in the installation's store this pass, with the service's key
     * on it: its name fits and the store answers. A public bucket with no web endpoint or no base
     * domain is made and fails all the same, so a setting added later serves it at once.
     */
    def provisioned: Boolean =
      made && status.store == "garage" && (plan match
        case ObjectStoragePlan.Waiting(None) | ObjectStoragePlan.Ready(_, None) => true
        case ObjectStoragePlan.Failed(Vector(problem))                          => Unserved(problem)
        case _                                                                  => false)

    /** Whether the service is told of it: made and ready, or supplied. */
    def told: Boolean = plan match
      case ObjectStoragePlan.Supplied          => true
      case ObjectStoragePlan.Ready(_, None)    => status.store == "garage"
      case ObjectStoragePlan.Ready(_, Some(_)) => status.store == "gcs"
      case _                                   => false

    /** The bucket as the cloud provider made it, once it is answered. */
    def inCloud: Option[CloudBucket] = plan match
      case ObjectStoragePlan.Ready(_, Some(c)) => Some(c)
      case _                                   => None

  /** Why a public bucket in Garage cannot be served: the store's web endpoint is not named. */
  val NoWebService: String =
    "a public bucket is served at the store's web endpoint, which the installation does not name " +
      "(ANKKA_OBJECT_STORE_WEB_SERVICE)"

  /**
   * Why a public bucket in Garage has no address: it is served at `storage.<base>`, and the
   * installation names no base domain. Without one the service would be handed a public bucket with
   * no address, which its runtime refuses at start.
   */
  val NoPublicAddress: String =
    "a public bucket is served at storage.<base domain>, and the installation names no base domain " +
      "(ANKKA_BASE_DOMAIN)"

  /**
   * The reasons a public bucket in Garage is made and still fails: a setting added later serves it.
   */
  private val Unserved: Set[String] = Set(NoWebService, NoPublicAddress)

  /**
   * Whether a named bucket is the installation's cloud account's (feature 055): as the unnamed one,
   * from where the operator last reported it made, else the installation's backend.
   */
  def bucketInCloud(
      settings: Settings,
      reported: Option[BucketStatus],
      service: Option[ObjectStorageStatus] = None
  ): Boolean =
    def isMade(phase: String) = phase == "Provisioned" || phase == "Recovered"
    val made                  = reported.filter(r => isMade(r.phase))
    // A move that has switched took every bucket the service had to the cloud (R11), and one named
    // since is made there; a bucket not yet made follows the unnamed one where that is made, so a
    // service's buckets share one store and one key; else the installation's backend.
    val inCloud =
      if service.flatMap(_.move).exists(_.state == "Switched") then true
      else
        made.map(_.store) match
          case Some(store) => store == "gcs"
          case None        =>
            service.filter(s => isMade(s.phase)) match
              case Some(unnamed) => unnamed.store == "gcs"
              case None          => settings.bucketBackend.contains(ObjectStoreBackend.Gcs)
    settings.cloud.isDefined && inCloud

  /**
   * Why a bucket named while the service's buckets move waits: until the move has switched (R11).
   */
  val WaitingOnMove: String = "waiting for the service's buckets to finish moving to the cloud"

  /**
   * Whether a named bucket waits for a move (R11): one is asked for and has not switched, and this
   * bucket was never made, so it is made in the target store once the move is done.
   */
  def waitsForMove(
      spec: AnkkaServiceSpec,
      reported: Option[BucketStatus],
      service: Option[ObjectStorageStatus]
  ): Boolean =
    spec.objectStorageMove.isDefined &&
      !service.flatMap(_.move).exists(_.state == "Switched") &&
      !reported.exists(r => r.phase == "Provisioned" || r.phase == "Recovered")

  /**
   * What to do about one named bucket, as `decide` does about the unnamed one, with its exposure: a
   * `public` bucket in Garage needs the store's web endpoint, and fails naming the setting without
   * it. A supplied bucket is the service's own and the store is asked nothing.
   */
  def decideBucket(
      spec: AnkkaServiceSpec,
      bucket: BucketSpec,
      settings: Settings,
      observed: ObjectStorageObservation,
      reported: Option[BucketStatus],
      cloud: Option[CloudBucketPlans] = None,
      service: Option[ObjectStorageStatus] = None
  ): NamedBucket =
    bucket.supplied match
      case Some(supplied) =>
        NamedBucket(
          bucket,
          supplied.bucket,
          ObjectStoragePlan.Supplied,
          BucketStatus(
            name = bucket.name,
            phase = "Supplied",
            bucket = supplied.bucket,
            exposure = "supplied"
          )
        )
      case None =>
        val named   = Buckets.named(spec.projectId, spec.serviceName, bucket.name)
        val inCloud = bucketInCloud(settings, reported, service)
        val plan    =
          if waitsForMove(spec, reported, service) then
            ObjectStoragePlan.Waiting(Some(WaitingOnMove))
          else if inCloud then cloud.fold(ObjectStoragePlan.Waiting(None))(fold(_, true))
          else
            val names = Buckets.problems(spec.projectId, spec.serviceName, bucket.name)
            if names.nonEmpty then ObjectStoragePlan.Failed(names)
            else if settings.objectStore.isEmpty then ObjectStoragePlan.Failed(Vector(NoStore))
            else if bucket.exposure == "public" && settings.objectStore
                .flatMap(_.webService)
                .isEmpty
            then ObjectStoragePlan.Failed(Vector(NoWebService))
            else if bucket.exposure == "public" && settings.baseDomain.isEmpty then
              ObjectStoragePlan.Failed(Vector(NoPublicAddress))
            else
              observed.unreachable match
                case Some(reason) => ObjectStoragePlan.Waiting(Some(reason))
                case None         =>
                  observed.bucketCreated match
                    case Some(created) if observed.keyAllowed =>
                      ObjectStoragePlan.Ready(
                        recovered = observed.resourceCreatedAt.exists(created.isBefore)
                      )
                    case _ => ObjectStoragePlan.Waiting(None)
        val inStore = plan match
          case ObjectStoragePlan.Ready(_, Some(c)) => c.bucket
          case _ if inCloud                        => ""
          case _                                   => named
        val address = plan match
          case ObjectStoragePlan.Ready(_, Some(c)) if bucket.exposure == "public" =>
            c.publicUrl.orElse(Some(Buckets.gcsPublicAddress(c.endpoint, c.bucket)))
          case ObjectStoragePlan.Ready(_, Some(c)) if bucket.exposure != "private" =>
            Some(Buckets.gcsPublicAddress(c.endpoint, c.bucket))
          case ObjectStoragePlan.Waiting(_) | ObjectStoragePlan.Ready(_, None)
              if bucket.exposure != "private" && !inCloud &&
                !waitsForMove(spec, reported, service) =>
            settings.baseDomain.map(base =>
              s"${Buckets.publicEndpoint(base, settings.httpsPort)}/$named"
            )
          case _ => None
        NamedBucket(
          bucket,
          named,
          plan,
          BucketStatus(
            name = bucket.name,
            phase = plan.reportedPhase.getOrElse(""),
            store = if inCloud then "gcs" else "garage",
            bucket = inStore,
            exposure = bucket.exposure,
            publicAddress = address,
            detail = plan match
              case ObjectStoragePlan.Failed(problems) => Some(problems.mkString("; "))
              case ObjectStoragePlan.Waiting(detail)  => detail
              case _                                  => None
          )
        )

  /**
   * Every named bucket the spec gives, decided from its own observation and its last status. A
   * bucket the store was not asked about is waiting.
   */
  def decideBuckets(
      spec: AnkkaServiceSpec,
      settings: Settings,
      observed: Map[String, ObjectStorageObservation],
      reported: Option[ObjectStorageStatus],
      cloud: Map[String, CloudBucketPlans] = Map.empty
  ): Vector[NamedBucket] =
    val last = reported.toList.flatMap(_.buckets).map(b => b.name -> b).toMap
    spec.buckets.toVector.map { b =>
      val named = Buckets.named(spec.projectId, spec.serviceName, b.name)
      decideBucket(
        spec,
        b,
        settings,
        observed.getOrElse(named, ObjectStorageObservation.empty),
        last.get(b.name),
        cloud.get(b.name),
        reported
      )
    }

  /**
   * The buckets the status names and the spec no longer does (R7): written back `Kept`, with their
   * last store, name and address, so a later pass still knows to remove a route and withhold the
   * variables. The platform never deletes a bucket. A supplied one was never the platform's, and is
   * forgotten.
   */
  def kept(spec: AnkkaServiceSpec, reported: Option[ObjectStorageStatus]): List[BucketStatus] =
    val named = spec.buckets.map(_.name).toSet
    reported.toList
      .flatMap(_.buckets)
      .filterNot(b => named(b.name) || b.exposure == "supplied")
      .map(_.copy(phase = "Kept", kept = true, detail = None))

  /**
   * Why the Deployment waits for a named bucket, if it does (feature 055): a bucket the platform
   * makes that is not yet made, whose variables an instance would otherwise start without.
   */
  def namedWithheld(named: Vector[NamedBucket]): Option[String] =
    named.collectFirst {
      case b if b.made && b.plan.isInstanceOf[ObjectStoragePlan.Waiting] =>
        b.status.detail.getOrElse(s"waiting for the bucket '${b.spec.name}' to be made")
    }

  /** The status's list: each named bucket in the spec's order, then the kept ones. */
  def bucketStatuses(
      decided: Vector[NamedBucket],
      spec: AnkkaServiceSpec,
      reported: Option[ObjectStorageStatus]
  ): List[BucketStatus] =
    decided.toList.map(_.status) ++ kept(spec, reported)

  /** The bucket's address on the internet, when its descriptor asks and there is a base domain. */
  def publicAddress(
      spec: AnkkaServiceSpec,
      settings: Settings,
      reported: Option[ObjectStorageStatus]
  ): Option[String] =
    for
      _ <- Option.when(spec.provisionObjectStorage && spec.exposeObjectStorage)(())
      // A cloud bucket is reached at the cloud's address, not through the installation's gateway.
      _    <- Option.when(!takesCloudPath(spec, settings, reported))(())
      base <- settings.baseDomain
    yield Buckets.publicAddress(spec.projectId, spec.serviceName, base, settings.httpsPort)

  /** The generation of the storage credential the Secret holds, as the last pass reported it. */
  def inPlace(resource: com.thinkmorestupidless.ankka.crd.AnkkaService): Int =
    Option(resource.getStatus)
      .flatMap(_.objectStorage)
      .map(_.credentialGeneration)
      .getOrElse(0)

  /**
   * The generation of the storage credential in place once this pass has run (feature 039): the one
   * a member asked for when the plan renders the credential, which issues it before the Deployment
   * is applied; otherwise the one already there. Rendering puts it on the pod template and the
   * status reports it, from this one function, so the two cannot disagree.
   */
  def credentialGeneration(plan: ObjectStoragePlan, spec: AnkkaServiceSpec, inPlace: Int): Int =
    plan match
      case ObjectStoragePlan.Waiting(None) | ObjectStoragePlan.Ready(_, _) =>
        math.max(spec.storageCredentialGeneration, inPlace)
      case _ => inPlace

  def status(
      plan: ObjectStoragePlan,
      spec: AnkkaServiceSpec,
      settings: Settings,
      reported: Option[ObjectStorageStatus],
      inPlace: Int = 0,
      named: Vector[NamedBucket] = Vector.empty
  ): Option[ObjectStorageStatus] =
    val listed = bucketStatuses(named, spec, reported)
    unnamedStatus(plan, spec, settings, reported, inPlace) match
      case Some(s) if listed.nonEmpty => Some(s.copy(buckets = listed))
      case None if listed.nonEmpty    =>
        // Only named buckets (feature 055): no phase of the unnamed one's, and the credential in
        // place, which the pod template carries, once the platform makes any of them.
        Some(
          ObjectStorageStatus(
            credentialGeneration =
              if named.exists(_.provisioned) then
                math.max(spec.storageCredentialGeneration, inPlace)
              else inPlace,
            buckets = listed
          )
        )
      case unchanged => unchanged

  private def unnamedStatus(
      plan: ObjectStoragePlan,
      spec: AnkkaServiceSpec,
      settings: Settings,
      reported: Option[ObjectStorageStatus],
      inPlace: Int
  ): Option[ObjectStorageStatus] =
    val inCloud = takesCloudPath(spec, settings, reported)
    // A cloud bucket's name is the provider's, known once it has answered; the store's is derived.
    val bucket = plan match
      case ObjectStoragePlan.Ready(_, Some(cloud)) => cloud.bucket
      case _ if inCloud                            => ""
      case _                                       => Buckets.name(spec.projectId, spec.serviceName)
    // A bucket the platform made says which store it is in and which credential is in place; an
    // object store of the service's own is neither the platform's to name. A bucket in the cloud
    // account is in Google Cloud Storage, `gcp` being the one provider there is (feature 044), and
    // its credential's generation is the provider's answer.
    statusOf(plan, spec, settings, reported, bucket).map(s =>
      if inCloud then
        s.copy(
          store = "gcs",
          credentialGeneration = cloudGeneration(plan),
          // Where the provider made it, as it reported, and the window it was asked to keep a
          // deleted object for (feature 039); an exposed bucket's address is the cloud's own.
          location = plan match
            case ObjectStoragePlan.Ready(_, Some(cloud)) => Some(cloud.location).filter(_.nonEmpty)
            case _                                       => None,
          softDeleteDays = settings.gcs.map(_.softDeleteDays),
          publicAddress = plan match
            case ObjectStoragePlan.Ready(_, Some(cloud)) if spec.exposeObjectStorage =>
              Some(s"${cloud.endpoint.stripSuffix("/")}/${cloud.bucket}")
            case _ => None
        )
      else if spec.provisionObjectStorage then
        s.copy(store = "garage", credentialGeneration = credentialGeneration(plan, spec, inPlace))
      else s
    )

  /** The credential generation a cloud provider reported in place, 0 until it has (feature 044). */
  def cloudGeneration(plan: ObjectStoragePlan): Int =
    plan match
      case ObjectStoragePlan.Ready(_, Some(cloud)) => cloud.credentialGeneration.toInt
      case _                                       => 0

  private def statusOf(
      plan: ObjectStoragePlan,
      spec: AnkkaServiceSpec,
      settings: Settings,
      reported: Option[ObjectStorageStatus],
      bucket: String
  ): Option[ObjectStorageStatus] =
    plan.reportedPhase.map { phase =>
      plan match
        case ObjectStoragePlan.Supplied         => ObjectStorageStatus(phase = phase)
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
            publicAddress = publicAddress(spec, settings, reported),
            detail = detail
          )
        case ObjectStoragePlan.Ready(recovered, _) =>
          ObjectStorageStatus(
            phase = phase,
            bucket = bucket,
            publicAddress = publicAddress(spec, settings, reported),
            recovered = recovered
          )
        case ObjectStoragePlan.NotAsked => ObjectStorageStatus(phase = phase)
    }
