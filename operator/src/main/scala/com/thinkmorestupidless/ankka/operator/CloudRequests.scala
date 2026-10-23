package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProject,
  AnkkaService,
  CloudKinds,
  CloudResource,
  CloudResourceSpec,
  CloudSubject
}
import io.fabric8.kubernetes.api.model.{ObjectMetaBuilder, OwnerReference}

import scala.jdk.CollectionConverters.*

/**
 * The six cloud requests the operator can write (feature 044), each a need of a platform feature in
 * the platform's words. Every function here builds an inert `CloudResource`; `Fabric8Executor`
 * applies it through `Action.EnsureCloudResource`, and nothing here reaches anything.
 *
 * What a request may ask is closed: `Keys.parameters` names every key each kind takes, and
 * `CloudRequestsSuite` holds every renderer to it. A key in a cloud's own words has no place to go.
 */
object CloudRequests:

  /** Every parameter and output key of the contract, by kind. */
  object Keys:
    val ServiceAccount: String  = "serviceAccount"
    val Identity: String        = "identity"
    val Own: String             = "own"
    val Read: String            = "read"
    val SecretName: String      = "secretName"
    val Entries: String         = "entries"
    val EntryGeneration: String = "entryGeneration"
    val Purpose: String         = "purpose"
    val Location: String        = "location"
    val Versioning: String      = "versioning"
    val SoftDeleteDays: String  = "softDeleteDays"
    val CorsOrigins: String     = "corsOrigins"
    val KmsKey: String          = "kmsKey"
    val Bucket: String          = "bucket"
    val Endpoint: String        = "endpoint"
    val Region: String          = "region"
    val Key: String             = "key"
    // Feature 039 (research R1a D3, D4).
    val NamePrefix: String                = "namePrefix"
    val NoncurrentVersionDays: String     = "noncurrentVersionDays"
    val ServiceAccountAnnotations: String = "serviceAccountAnnotations"
    // Feature 055 (research R10): a named bucket, a public one, and one credential over several.
    val Name: String      = "name"
    val Public: String    = "public"
    val Buckets: String   = "buckets"
    val PublicUrl: String = "publicUrl"

    /** What each kind asks: exactly these keys, no more and no fewer. */
    val parameters: Map[String, Set[String]] = Map(
      CloudKinds.Identity     -> Set(ServiceAccount),
      CloudKinds.SecretAccess -> Set(Identity, Own, Read),
      CloudKinds.SecretSync   -> Set(SecretName, Entries, EntryGeneration),
      CloudKinds.Bucket       -> Set(
        Purpose,
        Location,
        Versioning,
        SoftDeleteDays,
        CorsOrigins,
        KmsKey,
        NamePrefix,
        NoncurrentVersionDays,
        Name,
        Public
      ),
      CloudKinds.BucketCredential -> Set(Bucket, Buckets, Identity, SecretName),
      CloudKinds.WrappingKey      -> Set(Identity, Key)
    )

    /** What each kind's fulfilment answers with. */
    val outputs: Map[String, Set[String]] = Map(
      CloudKinds.Identity         -> Set(Identity, ServiceAccountAnnotations),
      CloudKinds.SecretAccess     -> Set.empty,
      CloudKinds.SecretSync       -> Set(EntryGeneration),
      CloudKinds.Bucket           -> Set(Bucket, Endpoint, Region, PublicUrl),
      CloudKinds.BucketCredential -> Set(SecretName),
      CloudKinds.WrappingKey      -> Set(Key)
    )

    /**
     * The keys a request of a kind may leave out (feature 055): the unnamed bucket's request names
     * no bucket and is never public, so it reads byte for byte as it did; a credential names
     * `bucket` when it reaches one, as it always has, and `buckets` when it reaches several, never
     * both.
     */
    val optional: Map[String, Set[String]] = Map(
      CloudKinds.Bucket           -> Set(Name, Public),
      CloudKinds.BucketCredential -> Set(Bucket, Buckets)
    )

    /**
     * The outputs an answer may leave out: `publicUrl` is a public bucket's alone (feature 055).
     */
    val optionalOutputs: Map[String, Set[String]] = Map(CloudKinds.Bucket -> Set(PublicUrl))

    /** What every request of a kind carries. */
    def required(kind: String): Set[String] =
      parameters.getOrElse(kind, Set.empty) -- optional.getOrElse(kind, Set.empty)

  /** What a `bucket` request's `purpose` may be. */
  object Purpose:
    val Service: String = "service"
    val Backup: String  = "backup"

  /**
   * Whom a request serves, and so where it lives, what it is labelled and what owns it: a service's
   * resource, or a project's.
   */
  final case class Requester(
      namespace: String,
      subject: CloudSubject,
      labels: Map[String, String],
      owner: OwnerReference
  ):
    /** A service's request is `<service>-<suffix>`; a project's `<project>.<suffix>`. */
    def name(suffix: String): String =
      if subject.service.nonEmpty then Names.CloudRequest.ofService(subject.service, suffix)
      else Names.CloudRequest.ofProject(subject.project, suffix)

  object Requester:
    def of(resource: AnkkaService): Requester =
      val spec = resource.getSpec
      Requester(
        namespace = resource.getMetadata.getNamespace,
        subject = CloudSubject(spec.projectId, spec.serviceName),
        labels = Labels.identity(spec.projectId, spec.serviceName),
        owner = Labels.ownerReference(resource)
      )

    def of(project: AnkkaProject): Requester =
      val projectId = project.getSpec.projectId
      Requester(
        namespace = project.getMetadata.getNamespace,
        subject = CloudSubject(projectId),
        labels = Map(
          Labels.ManagedByKey -> Labels.ManagedByAnkka,
          Labels.ProjectKey   -> projectId
        ),
        owner = Labels.ownerReference(project)
      )

  /**
   * What a descriptor asks of a bucket beyond having one. Feature 034's descriptor asks nothing
   * more; feature 039 adds these, and their defaults are what a bucket had without them.
   *
   * @param namePrefix
   *   the installation's prefix, which starts the bucket's name in a namespace shared with every
   *   other customer of the cloud's; empty for none
   * @param noncurrentVersionDays
   *   after how many days a version no longer current is deleted; none keeps it until the
   *   soft-delete window and a member's deletion say otherwise
   */
  final case class BucketAsk(
      versioning: Boolean = false,
      softDeleteDays: Int = 0,
      corsOrigins: Vector[String] = Vector.empty,
      namePrefix: String = "",
      noncurrentVersionDays: Option[Int] = None,
      /** The wrapping key to ask for, when not the installation's as it is now. */
      kmsKey: Option[String] = None
  )

  /** What a named bucket's request asks beyond the unnamed one's (feature 055). */
  final case class NamedAsk(name: String, public: Boolean)

  /** A cloud identity for a Kubernetes ServiceAccount in the request's namespace. */
  def identity(cloud: CloudSettings, by: Requester, serviceAccount: String): CloudResource =
    request(
      cloud,
      by,
      CloudKinds.Identity,
      Names.CloudRequest.IdentitySuffix,
      Map(Keys.ServiceAccount -> serviceAccount)
    )

  /**
   * What a cloud identity may own and read of the cloud account's secrets, each by id prefix: a
   * service names its secrets as it runs, and a create cannot be limited by a name (feature 038).
   */
  def secretAccess(
      cloud: CloudSettings,
      by: Requester,
      identity: String,
      own: Seq[String],
      read: Seq[String]
  ): CloudResource =
    request(
      cloud,
      by,
      CloudKinds.SecretAccess,
      Names.CloudRequest.SecretAccessSuffix,
      Map(Keys.Identity -> identity, Keys.Own -> list(own), Keys.Read -> list(read))
    )

  /**
   * A Kubernetes Secret kept in step with a project's entries, each `NAME=id`: one request per
   * project secret, `<project>.secret-sync.<secret>`, since a project may have several.
   */
  def secretSync(
      cloud: CloudSettings,
      by: Requester,
      secretName: String,
      entries: Map[String, String],
      entryGeneration: Long
  ): CloudResource =
    request(
      cloud,
      by,
      CloudKinds.SecretSync,
      s"${Names.CloudRequest.SecretSyncSuffix}.$secretName",
      Map(
        Keys.SecretName      -> secretName,
        Keys.Entries         -> list(entries.toVector.sorted.map((k, v) => s"$k=$v")),
        Keys.EntryGeneration -> entryGeneration.toString
      )
    )

  /**
   * A bucket. A service's is `purpose: service`; a project's is its backups' (feature 041), named
   * so no service's can collide with it.
   */
  def bucket(
      cloud: CloudSettings,
      by: Requester,
      purpose: String,
      location: String,
      ask: BucketAsk,
      annotations: Map[String, String] = Map.empty,
      named: Option[NamedAsk] = None
  ): CloudResource =
    request(
      cloud,
      by,
      CloudKinds.Bucket,
      if purpose == Purpose.Backup then Names.CloudRequest.BackupBucketSuffix
      else named.fold(Names.CloudRequest.BucketSuffix)(n => Names.CloudRequest.namedBucket(n.name)),
      named.fold(Map.empty[String, String])(n =>
        Map(Keys.Name -> n.name, Keys.Public -> n.public.toString)
      ) ++ Map(
        Keys.Purpose               -> purpose,
        Keys.Location              -> location,
        Keys.Versioning            -> ask.versioning.toString,
        Keys.SoftDeleteDays        -> ask.softDeleteDays.toString,
        Keys.CorsOrigins           -> list(ask.corsOrigins),
        Keys.KmsKey                -> ask.kmsKey.orElse(cloud.kmsKey).getOrElse(""),
        Keys.NamePrefix            -> ask.namePrefix,
        Keys.NoncurrentVersionDays -> ask.noncurrentVersionDays.fold("")(_.toString)
      ),
      annotations = annotations
    )

  /**
   * A credential by which one cloud identity reaches one bucket, written once into a named Secret.
   * A service's is its storage credential; a project's is its database's for its backups.
   */
  def bucketCredential(
      cloud: CloudSettings,
      by: Requester,
      purpose: String,
      bucket: String,
      identity: String,
      secretName: String,
      generation: Long
  ): CloudResource =
    bucketCredential(cloud, by, purpose, Vector(bucket), identity, secretName, generation)

  /**
   * One credential by which one cloud identity reaches every bucket of a service's (feature 055):
   * one key, granted on each. A credential over one bucket is asked as it always was, by `bucket`,
   * so a provider from before named buckets answers it unchanged; over several, by `buckets`.
   */
  def bucketCredential(
      cloud: CloudSettings,
      by: Requester,
      purpose: String,
      buckets: Vector[String],
      identity: String,
      secretName: String,
      generation: Long
  ): CloudResource =
    request(
      cloud,
      by,
      CloudKinds.BucketCredential,
      if purpose == Purpose.Backup then Names.CloudRequest.BackupCredentialSuffix
      else Names.CloudRequest.StorageCredentialSuffix,
      Map(
        (if buckets.size == 1 then Keys.Bucket else Keys.Buckets) -> list(buckets),
        Keys.Identity                                             -> identity,
        Keys.SecretName                                           -> secretName
      ),
      credentialGeneration = generation
    )

  /** The buckets a credential request reaches, whichever key it names them by. */
  def bucketsOf(parameters: Map[String, String]): Vector[String] =
    parameters
      .get(Keys.Buckets)
      .orElse(parameters.get(Keys.Bucket))
      .toVector
      .flatMap(_.split(','))
      .filter(_.nonEmpty)

  /** That a cloud identity may wrap with the installation's wrapping key. */
  def wrappingKey(
      cloud: CloudSettings,
      by: Requester,
      identity: String,
      key: String
  ): CloudResource =
    request(
      cloud,
      by,
      CloudKinds.WrappingKey,
      Names.CloudRequest.WrappingKeySuffix,
      Map(Keys.Identity -> identity, Keys.Key -> key)
    )

  /** A list parameter: comma-separated, as the contract says. */
  def list(values: Seq[String]): String = values.mkString(",")

  private def request(
      cloud: CloudSettings,
      by: Requester,
      kind: String,
      suffix: String,
      parameters: Map[String, String],
      credentialGeneration: Long = 0L,
      annotations: Map[String, String] = Map.empty
  ): CloudResource =
    val resource = new CloudResource
    val meta     = new ObjectMetaBuilder()
      .withNamespace(by.namespace)
      .withName(by.name(suffix))
      .withLabels(by.labels.asJava)
      .withOwnerReferences(by.owner)
      .build()
    if annotations.nonEmpty then meta.setAnnotations(annotations.asJava)
    resource.setMetadata(meta)
    resource.setSpec(
      CloudResourceSpec(
        provider = cloud.provider,
        kind = kind,
        subject = by.subject,
        credentialGeneration = credentialGeneration,
        parameters = parameters
      )
    )
    resource
