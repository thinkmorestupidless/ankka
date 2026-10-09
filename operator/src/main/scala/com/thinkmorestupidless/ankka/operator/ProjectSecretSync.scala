package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.secrets.DerivedIds
import com.thinkmorestupidless.ankka.crd.{
  AnkkaProject,
  AnkkaServiceSpec,
  CloudResource,
  CloudResourceSpec,
  ProjectSecretEntry
}

/**
 * A project's secrets kept in step with Secret Manager (feature 038), by the installation's cloud
 * provider (feature 044): one `secret-sync` request per project secret, naming each entry's id in
 * the account and the secret's generation, so the provider keeps the project's Kubernetes Secret of
 * that name holding what Secret Manager holds. A descriptor's `secretKeyRef` reads that Secret as
 * it always did.
 *
 * A service that takes a variable from a project secret is not rolled out until its secret is in
 * step: started before, it would read the value it had before the change. Everything here is pure;
 * the reconcilers render and observe.
 */
object ProjectSecretSync:

  /**
   * Whether project secrets are kept in the cloud account and a cloud provider keeps them in step.
   */
  def takesCloudPath(settings: Settings): Boolean =
    settings.cloud.isDefined && settings.secretStore.backend.contains("secret-manager")

  def requestName(projectId: String, secret: String): String =
    Names.CloudRequest.ofProject(projectId, s"${Names.CloudRequest.SecretSyncSuffix}.$secret")

  /**
   * The one request for one project secret, each entry by the id the control plane wrote it under.
   */
  def request(
      cloud: CloudSettings,
      project: AnkkaProject,
      secret: ProjectSecretEntry
  ): CloudResource =
    val projectId = project.getSpec.projectId
    CloudRequests.secretSync(
      cloud,
      CloudRequests.Requester.of(project),
      secret.name,
      secret.entries.map(e => e -> DerivedIds.projectEntry(projectId, secret.name, e)).toMap,
      secret.entryGeneration
    )

  /**
   * Every project secret's request, or none when the project's secrets are not kept in the cloud.
   */
  def requests(project: AnkkaProject, settings: Settings): Vector[CloudResource] =
    settings.cloud
      .filter(_ => takesCloudPath(settings))
      .toVector
      .flatMap(cloud => project.getSpec.secrets.toVector.map(request(cloud, project, _)))

  /** The project secrets a service's descriptor takes a variable from. */
  def referenced(
      spec: AnkkaServiceSpec,
      secrets: Vector[ProjectSecretEntry]
  ): Vector[ProjectSecretEntry] =
    val named = spec.env.flatMap(_.secretName).toSet
    secrets.filter(s => named(s.name))

  /** What a service says while one of its project secrets is not in step yet. */
  def waiting(secret: String): String =
    s"waiting on the cloud provider to sync the project secret '$secret'"

  def failed(secret: String, detail: String): String =
    s"the cloud provider could not sync the project secret '$secret': $detail"

  /** What the provider is asked by, for deciding an answer: only the provider's name is read. */
  def provider(cloud: CloudSettings): CloudResource =
    val r = new CloudResource
    r.setSpec(CloudResourceSpec(provider = cloud.provider))
    r

  /**
   * Whether a service is held for one project secret: in step once the provider reports this
   * generation or a later one synced; a refusal is the service's failure, with the provider's
   * words.
   */
  def hold(secret: ProjectSecretEntry, plan: CloudPlan): Option[SecretAccess.Hold] =
    plan match
      case CloudPlan.Ready(outputs, _, _)
          if outputs
            .get(CloudRequests.Keys.EntryGeneration)
            .flatMap(_.toLongOption)
            .exists(_ >= secret.entryGeneration) =>
        None
      case CloudPlan.Failed(detail) => Some(SecretAccess.Hold.Refused(failed(secret.name, detail)))
      case other => Some(SecretAccess.Hold.Waiting(other.said.getOrElse(waiting(secret.name))))
