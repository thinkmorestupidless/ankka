package com.thinkmorestupidless.ankka.crd

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, ShortNames, Version}

/**
 * Who a cloud request serves (feature 044): a project, and a service of it when there is one. A
 * project's own requests (its backups' bucket, its secrets kept in step) leave `service` empty.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class CloudSubject(project: String = "", service: String = "")

/**
 * What the operator asks the installation's cloud provider for. Written by the operator alone, by
 * server-side apply; never by the provider, whose grant has no verb on it but reading.
 *
 * Every parameter is a string, in the platform's words and no cloud's: a list is comma-separated, a
 * boolean `"true"` or `"false"`, a number its decimal text. The keys each kind takes are named in
 * `docs/platform/cloud-provider.md` and, in the operator, `CloudRequests.Keys`.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class CloudResourceSpec(
    /** `ANKKA_CLOUD_PROVIDER`, copied in; a provider fulfils only requests naming it. */
    provider: String = "",
    /** One of `CloudKinds.all`. */
    kind: String = "",
    subject: CloudSubject = CloudSubject(),
    /**
     * A count on a `bucket-credential`: raising it asks for a new credential in the same Secret.
     * Zero, and so absent from the wire, on every other kind.
     */
    credentialGeneration: Long = 0L,
    parameters: Map[String, String] = Map.empty
)

/**
 * The provider's answer. Written through the status subresource by the provider alone; the operator
 * only reads it, and acts on none whose `observedGeneration` is behind the request's.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class CloudResourceStatus(
    /** The `metadata.generation` the rest describes. Absent until a provider has read it once. */
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    observedGeneration: Option[Long] = None,
    /** One of `CloudKinds.phases`. */
    phase: String = "",
    /** Why it waits or failed, in the provider's words. Never a secret. */
    detail: Option[String] = None,
    /** The account the thing was made in. */
    account: String = "",
    /** The location it was made in, in the installation's words. */
    location: String = "",
    /** The generation of the credential now in the Secret (`bucket-credential` only). */
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    credentialGeneration: Option[Long] = None,
    /** When `credentialGeneration` was reported in place, RFC 3339: the grace counts from here. */
    credentialReportedAt: Option[String] = None,
    /** The thing existed before this request. */
    recovered: Boolean = false,
    /** What fulfilled it, such as `ankka-gcp 0.1.0`. */
    providerVersion: String = "",
    /** The kind's outputs, named in the contract. */
    outputs: Map[String, String] = Map.empty
)

/**
 * One request to the installation's cloud provider: a need of a platform feature, in the platform's
 * words. Owned by the `AnkkaService` or `AnkkaProject` it serves, so it goes with it; nothing in
 * the cloud goes with it.
 */
@Group("ankka.thinkmorestupidless.com")
@Version("v1alpha1")
@Kind("CloudResource")
@Plural("cloudresources")
@ShortNames(Array("cres"))
class CloudResource extends CustomResource[CloudResourceSpec, CloudResourceStatus] with Namespaced:
  override protected def initSpec(): CloudResourceSpec = CloudResourceSpec()

  /** Null until a provider has answered, as `AnkkaService`'s is until the operator has. */
  override protected def initStatus(): CloudResourceStatus = null

object CloudResource:
  def apply(namespace: String, name: String, spec: CloudResourceSpec): CloudResource =
    val resource = new CloudResource
    resource.setMetadata(
      new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withNamespace(namespace)
        .withName(name)
        .build()
    )
    resource.setSpec(spec)
    resource

/** The closed vocabulary of the contract: six kinds, four phases. */
object CloudKinds:
  val Identity: String         = "identity"
  val SecretAccess: String     = "secret-access"
  val SecretSync: String       = "secret-sync"
  val Bucket: String           = "bucket"
  val BucketCredential: String = "bucket-credential"
  val WrappingKey: String      = "wrapping-key"

  /** Every kind, in the contract's order. A provider implements all of them or fails the rest. */
  val all: Vector[String] =
    Vector(Identity, SecretAccess, SecretSync, Bucket, BucketCredential, WrappingKey)

  val Waiting: String   = "Waiting"
  val Ready: String     = "Ready"
  val Recovered: String = "Recovered"
  val Failed: String    = "Failed"

  val phases: Vector[String] = Vector(Waiting, Ready, Recovered, Failed)
