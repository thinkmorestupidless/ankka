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

    /** What each kind asks: exactly these keys, no more and no fewer. */
    val parameters: Map[String, Set[String]] = Map(
      CloudKinds.Identity     -> Set(ServiceAccount),
      CloudKinds.SecretAccess -> Set(Identity, Own, Read),
      CloudKinds.SecretSync   -> Set(SecretName, Entries, EntryGeneration),
      CloudKinds.Bucket -> Set(Purpose, Location, Versioning, SoftDeleteDays, CorsOrigins, KmsKey),
      CloudKinds.BucketCredential -> Set(Bucket, Identity, SecretName),
      CloudKinds.WrappingKey      -> Set(Identity, Key)
    )

    /** What each kind's fulfilment answers with. */
    val outputs: Map[String, Set[String]] = Map(
      CloudKinds.Identity         -> Set(Identity),
      CloudKinds.SecretAccess     -> Set.empty,
      CloudKinds.SecretSync       -> Set(EntryGeneration),
      CloudKinds.Bucket           -> Set(Bucket, Endpoint, Region),
      CloudKinds.BucketCredential -> Set(SecretName),
      CloudKinds.WrappingKey      -> Set(Key)
    )

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
   */
  final case class BucketAsk(
      versioning: Boolean = false,
      softDeleteDays: Int = 0,
      corsOrigins: Vector[String] = Vector.empty
  )

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

  /** A Kubernetes Secret kept in step with a project's entries, each `NAME=id`. */
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
      Names.CloudRequest.SecretSyncSuffix,
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
      ask: BucketAsk
  ): CloudResource =
    request(
      cloud,
      by,
      CloudKinds.Bucket,
      if purpose == Purpose.Backup then Names.CloudRequest.BackupBucketSuffix
      else Names.CloudRequest.BucketSuffix,
      Map(
        Keys.Purpose        -> purpose,
        Keys.Location       -> location,
        Keys.Versioning     -> ask.versioning.toString,
        Keys.SoftDeleteDays -> ask.softDeleteDays.toString,
        Keys.CorsOrigins    -> list(ask.corsOrigins),
        Keys.KmsKey         -> cloud.kmsKey.getOrElse("")
      )
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
    request(
      cloud,
      by,
      CloudKinds.BucketCredential,
      if purpose == Purpose.Backup then Names.CloudRequest.BackupCredentialSuffix
      else Names.CloudRequest.StorageCredentialSuffix,
      Map(Keys.Bucket -> bucket, Keys.Identity -> identity, Keys.SecretName -> secretName),
      credentialGeneration = generation
    )

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
      credentialGeneration: Long = 0L
  ): CloudResource =
    val resource = new CloudResource
    resource.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace(by.namespace)
        .withName(by.name(suffix))
        .withLabels(by.labels.asJava)
        .withOwnerReferences(by.owner)
        .build()
    )
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
