package com.thinkmorestupidless.ankka.crd

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, ShortNames, Version}

/**
 * What a project declares that is not a service's (feature 027): its topics on the installation's
 * broker. Written by the control plane from the project's declarations, never by the operator.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AnkkaProjectSpec(
    projectId: String = "",
    topics: List[ProjectTopicEntry] = Nil,
    /** Brokers the project declares beside the installation's (feature 037). */
    brokers: List[ProjectBrokerEntry] = Nil,
    /**
     * The project's secrets by name and entry, never a value (feature 038): what the cloud provider
     * keeps the project's secrets in the cluster in step with, on the Secret Manager backend.
     */
    secrets: List[ProjectSecretEntry] = Nil,
    /**
     * A fingerprint of `secrets` and when each entry was last set: the provider reports the one it
     * has synced, and a service that takes a variable from an entry waits until the two are equal.
     */
    secretsFingerprint: Option[String] = None
)

/** One project secret: its name and its entries' names. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectSecretEntry(
    name: String = "",
    entries: List[String] = Nil,
    /**
     * When the secret was last set, in epoch milliseconds: the generation the cloud provider keeps
     * the Secret in step with, so a value set again is synced again though no name changed.
     */
    entryGeneration: Long = 0L
)

/**
 * One declared topic: its name as the project's components use it, its partitions, and when the
 * project declared it (RFC 3339), so a topic the broker held from before can be told apart.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectTopicEntry(
    name: String = "",
    partitions: Int = 1,
    declaredAt: String = "",
    /** Feature 037: the broker keeps the last message under each key. */
    compacted: Boolean = false,
    /**
     * Feature 037: the contract every side must state, and the fingerprint of its schema. Flat: the
     * schema suite checks one level.
     */
    contractName: Option[String] = None,
    contractFingerprint: Option[String] = None
)

/**
 * A broker a project declares by name (feature 037): where it is, the shape of its credential
 * (`certificate` or `sasl`), and the project secret holding it, which every service's platform
 * container mounts.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectBrokerEntry(
    name: String = "",
    bootstrap: String = "",
    shape: String = "",
    secretName: String = "",
    declaredAt: String = ""
)

/** How far the operator has got with one declared topic. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectTopicStatus(
    name: String = "",
    /** "Waiting", "Provisioned", "Recovered" or "Failed". */
    phase: String = "",
    /** The partitions the topic's resource asks for, when it exists. */
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    partitions: Option[Int] = None,
    detail: Option[String] = None,
    @JsonDeserialize(contentAs = classOf[java.lang.Boolean])
    compacted: Option[Boolean] = None
)

/** What the operator observed of the project's topics. Written to the status subresource only. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AnkkaProjectStatus(topics: List[ProjectTopicStatus] = Nil)

@Group("ankka.thinkmorestupidless.com")
@Version("v1alpha1")
@Kind("AnkkaProject")
@Plural("ankkaprojects")
@ShortNames(Array("aproj"))
class AnkkaProject extends CustomResource[AnkkaProjectSpec, AnkkaProjectStatus] with Namespaced:
  override protected def initSpec(): AnkkaProjectSpec = AnkkaProjectSpec()

  /** Null until the operator has reported, as `AnkkaService`'s is. */
  override protected def initStatus(): AnkkaProjectStatus = null

object AnkkaProject:
  def apply(namespace: String, name: String, spec: AnkkaProjectSpec): AnkkaProject =
    val resource = new AnkkaProject
    resource.setMetadata(
      new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withNamespace(namespace)
        .withName(name)
        .build()
    )
    resource.setSpec(spec)
    resource
