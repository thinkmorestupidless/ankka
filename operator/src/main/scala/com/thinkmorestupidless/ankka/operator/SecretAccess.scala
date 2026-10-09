package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.secrets.DerivedIds
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec, CloudResource}

/**
 * A service's access to its secrets on Secret Manager (feature 038), asked of the installation's
 * cloud provider (feature 044): a cloud identity for the service's ServiceAccount, the one a cloud
 * bucket asks for too, and a `secret-access` request naming that identity, which owns everything
 * under the service's own prefix and reads everything under its project's.
 *
 * By prefix, not by id: a service names its secrets while it runs, so no request can list them, and
 * an IAM condition on a name cannot limit a create. `DerivedIds` is the one derivation of both, the
 * file the runtime names its secrets with.
 *
 * Until the provider has granted it, the service's Deployment is not applied: an instance started
 * without its grant would be refused every read, and a rollout would replace instances that could
 * read with ones that cannot. Everything here is pure; `ServiceReconciler` observes the answers.
 */
object SecretAccess:

  val WaitingOnProvider: String = "waiting on the cloud provider for access to its secrets"

  def refused(detail: String): String =
    s"the cloud provider refused access to its secrets: $detail"

  /** Why a service's Deployment is not applied on this pass, for its access to its secrets. */
  enum Hold:
    /** Not answered yet, or the provider is still working: an update in progress. */
    case Waiting(detail: String)

    /** The provider refused: the service has failed, and says the provider's reason. */
    case Refused(detail: String)

  /**
   * Whether this service's secrets are kept in the installation's cloud account: Secret Manager is
   * the backend and a cloud provider fulfils requests. Without a provider the grant is made by hand
   * and nothing is asked. A web-hosted service's program holds no secret store.
   */
  def takesCloudPath(spec: AnkkaServiceSpec, settings: Settings): Boolean =
    settings.cloud.isDefined && settings.secretStore.backend.contains("secret-manager") &&
      spec.hosting != Rendering.WebHosting

  /** The cloud identity for the service's ServiceAccount, the request a bucket asks for too. */
  def identityRequest(resource: AnkkaService, cloud: CloudSettings): CloudResource =
    CloudRequests.identity(
      cloud,
      CloudRequests.Requester.of(resource),
      Names.serviceAccount(resource.getSpec.serviceName)
    )

  /** The access request, once the identity it grants is known by what the provider made. */
  def accessRequest(resource: AnkkaService, cloud: CloudSettings, identity: String): CloudResource =
    val spec = resource.getSpec
    CloudRequests.secretAccess(
      cloud,
      CloudRequests.Requester.of(resource),
      identity,
      own = Seq(DerivedIds.servicePrefix(spec.projectId, spec.serviceName)),
      read = Seq(DerivedIds.projectPrefix(spec.projectId))
    )

  /**
   * What holds the Deployment back, from the identity's answer and the access request's, which is
   * `None` while there is no identity to name. A refusal of either is the provider's last word for
   * this generation; anything unanswered is waiting.
   */
  def hold(identity: CloudPlan, access: Option[CloudPlan]): Option[Hold] =
    (identity, access) match
      case (CloudPlan.Failed(detail), _)                  => Some(Hold.Refused(refused(detail)))
      case (_, Some(CloudPlan.Failed(detail)))            => Some(Hold.Refused(refused(detail)))
      case (_: CloudPlan.Ready, Some(_: CloudPlan.Ready)) => None
      case (plan, None)    => Some(Hold.Waiting(plan.said.getOrElse(WaitingOnProvider)))
      case (_, Some(plan)) => Some(Hold.Waiting(plan.said.getOrElse(WaitingOnProvider)))
