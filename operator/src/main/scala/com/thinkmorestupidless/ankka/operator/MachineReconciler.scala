package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaMachine,
  AnkkaMachineSpec,
  AnkkaMachineStatus,
  AnkkaProject
}
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException}
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/**
 * Gives a registered machine its user on the installation's broker (feature 040), from its
 * `AnkkaMachine` and every `AnkkaProject` grant naming it, and reports how far it has got. Keyed by
 * the machine's resource name, `<organization>.<name>`; a machine whose resource is gone keeps a
 * user that grants it nothing. An installation with no broker gives a machine nothing.
 */
final class MachineReconciler(client: KubernetesClient, settings: Settings, executor: Executor):

  private val log = LoggerFactory.getLogger(classOf[MachineReconciler])

  def reconcile(ref: ServiceRef): Unit =
    settings.broker match
      case None => log.debug("no broker; machine {} is given nothing", ref.name)
      case Some(broker) =>
        MachineReconciler.parse(ref.name) match
          case None => log.warn("{} is not a machine's name", ref.name)
          case Some((organizationId, name)) =>
            val resource =
              Option(client.resources(classOf[AnkkaMachine]).withName(ref.name).get())
            val spec     = resource.flatMap(r => Option(r.getSpec))
            val projects = projectSpecs()
            val actions = MachineReconciler.actions(
              organizationId,
              name,
              spec,
              MachineRendering.granted(projects, organizationId, name),
              settings.machineDefaults,
              broker,
              executor
                .observeBroker(broker.namespace, MachineRendering.userName(organizationId, name)),
              resource.flatMap(r => Option(r.getStatus))
            )
            actions.foreach(executor.execute)

  private def projectSpecs(): Vector[com.thinkmorestupidless.ankka.crd.AnkkaProjectSpec] =
    try
      client
        .resources(classOf[AnkkaProject])
        .inAnyNamespace()
        .list()
        .getItems
        .asScala
        .toVector
        .flatMap(p => Option(p.getSpec))
    catch case e: KubernetesClientException if e.getCode == 404 => Vector.empty

object MachineReconciler:

  /** `<organization>.<name>` read back: both are DNS labels, so the first dot divides them. */
  def parse(resourceName: String): Option[(String, String)] =
    resourceName.indexOf('.') match
      case -1 => None
      case at =>
        val (organizationId, name) = (resourceName.take(at), resourceName.drop(at + 1))
        Option.when(organizationId.nonEmpty && name.nonEmpty && !name.contains('.'))(
          (organizationId, name)
        )

  /**
   * One pass, as values: the user, then the status when the machine's resource is there and the
   * status would say something new.
   */
  def actions(
      organizationId: String,
      name: String,
      spec: Option[AnkkaMachineSpec],
      granted: Vector[GrantedTopic],
      defaults: MachineDefaults,
      broker: BrokerSettings,
      observed: BrokerObservation,
      current: Option[AnkkaMachineStatus]
  ): Vector[Action] =
    val user = MachineRendering.user(organizationId, name, spec, granted, defaults, broker)
    val next = AnkkaMachineStatus(
      user = Some(MachineRendering.userName(organizationId, name)),
      phase = observed.user match
        case s if s.ready.contains(true)  => "Provisioned"
        case s if s.ready.contains(false) => "Failed"
        case _                            => "Waiting"
      ,
      detail = observed.user.message.filter(_ => observed.user.ready.contains(false))
    )
    Vector(Action.EnsureKafkaUser(user)) ++
      Option.when(spec.isDefined && !current.contains(next))(
        Action.SetMachineStatus(AnkkaMachine.nameOf(organizationId, name), next)
      )
