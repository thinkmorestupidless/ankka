package nakka.operator

import io.fabric8.kubernetes.api.model.{OwnerReference, OwnerReferenceBuilder}
import nakka.crd.{NakkaService, NakkaServiceDefinition}

import scala.jdk.CollectionConverters.*

/**
 * Ownership and identity.
 *
 * Two mechanisms doing different jobs. The owner reference is what makes deletion cascade — it is
 * structural, enforced by the API server, and the reason this design has no orphan sweep anywhere
 * in it. The labels are for selectors and for `kubectl`, and one of them doubles as the test for
 * "is this ours".
 */
object Labels:

  val ManagedByKey: String = "app.kubernetes.io/managed-by"
  val NameKey: String      = "app.kubernetes.io/name"
  val ProjectKey: String   = "nakka.thinkmorestupidless.com/project"
  val ServiceKey: String   = "nakka.thinkmorestupidless.com/service"

  /**
   * The generation is an annotation, never a label.
   *
   * On the *pod template* it is what makes a rolling replacement happen, which is why restart and
   * apply are one mechanism. In a label it would end up in the selector, and a Deployment's
   * selector is immutable — putting a value that changes every apply there bricks the service at
   * generation 2, permanently.
   */
  val GenerationKey: String = "nakka.thinkmorestupidless.com/generation"

  /**
   * On the pod template, and the only thing there that changes on a restart. The generation used to
   * be there too, which made every apply — including a pure scale — roll every pod.
   */
  val RestartsKey: String = "nakka.thinkmorestupidless.com/restarts"

  val ManagedByNakka: String = "nakka"

  /**
   * On every pod template the operator renders, and in the selector Cluster Bootstrap discovers
   * contact points with — so that only pods which can *answer* a bootstrap probe are ever asked.
   *
   * Found by upgrading a real cluster from a feature-003 image: the old pods were Ready by their
   * tcp probe, so the rolling update kept them; the new pods discovered them by the identity
   * labels, could not reach a management port that did not exist, and the join decider refused to
   * form a cluster while any contact point was unanswered — a deadlock the empty-cluster test
   * suites cannot see. NOT in the Deployment's `spec.selector` (immutable) nor the Service's (old
   * pods should keep serving while they last).
   */
  val FormationKey: String       = "nakka.thinkmorestupidless.com/formation"
  val FormationBootstrap: String = "bootstrap"

  /** `managed-by=nakka` alone is the ownership test. No label, not ours, never touched. */
  def ownedByNakka(labels: Map[String, String]): Boolean =
    labels.get(ManagedByKey).contains(ManagedByNakka)

  def ownedByNakka(labels: java.util.Map[String, String]): Boolean =
    labels != null && ownedByNakka(labels.asScala.toMap)

  /**
   * The identity of one service's objects.
   *
   * Also the Deployment's selector, which is why nothing that changes may appear here.
   */
  def identity(projectId: String, serviceName: String): Map[String, String] =
    Map(
      ManagedByKey -> ManagedByNakka,
      NameKey      -> serviceName,
      ProjectKey   -> projectId,
      ServiceKey   -> serviceName
    )

  /**
   * Descriptor labels merged *under* the identity labels.
   *
   * A descriptor must not be able to override its own identity — that would let one service
   * impersonate another, or disown itself so the operator stopped managing it.
   */
  def merged(
      projectId: String,
      serviceName: String,
      extra: Map[String, String]
  ): Map[String, String] =
    extra ++ identity(projectId, serviceName)

  /**
   * Binds an object's life to the resource's.
   *
   * Carries the uid, not just the name: a resource deleted and recreated under the same name gets a
   * new uid, and children of the old one are then garbage-collected rather than silently adopted by
   * the new one.
   */
  def ownerReference(resource: NakkaService): OwnerReference =
    new OwnerReferenceBuilder()
      .withApiVersion(NakkaServiceDefinition.apiVersion)
      .withKind(NakkaServiceDefinition.kind)
      .withName(resource.getMetadata.getName)
      .withUid(resource.getMetadata.getUid)
      .withController(true)
      .withBlockOwnerDeletion(true)
      .build()
