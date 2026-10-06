package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaProject, AnkkaProjectSpec}
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.{Logger, LoggerFactory}

/**
 * One pass for one project's topics (feature 027): read the declarations, make each topic on the
 * installation's broker, report how far each has got.
 *
 * Level-triggered, as a service's pass is. A topic no longer declared is neither rendered nor
 * reported, and its `KafkaTopic` is left where it is: nothing the platform made on the broker is
 * removed by it.
 */
final class ProjectReconciler(client: KubernetesClient, settings: Settings, executor: Executor)
    extends Reconciler:

  private val log: Logger = LoggerFactory.getLogger("ankka.operator.projects")

  def reconcile(ref: ServiceRef): Unit =
    Option(
      client.resources(classOf[AnkkaProject]).inNamespace(ref.namespace).withName(ref.name).get()
    ) match
      case None => log.debug("project {} no longer exists; nothing to do", ref)
      case Some(project) =>
        val spec  = Option(project.getSpec).getOrElse(AnkkaProjectSpec())
        val names = spec.topics.toVector.map(t => BrokerNames.topic(spec.projectId, t.name))
        val observed =
          settings.broker.fold(Map.empty)(b => executor.observeTopics(b.namespace, names))
        ProjectReconciler
          .actions(ref, spec, settings.broker, observed, Option(project.getStatus))
          .foreach(executor.execute)

object ProjectReconciler:

  /**
   * Everything one pass does, as values: a topic for each declaration that would not shrink one the
   * broker has, then the status, unless it says what the resource already says.
   */
  def actions(
      ref: ServiceRef,
      spec: AnkkaProjectSpec,
      broker: Option[BrokerSettings],
      observed: Map[String, TopicState],
      current: Option[com.thinkmorestupidless.ankka.crd.AnkkaProjectStatus]
  ): Vector[Action] =
    val topics = broker.toVector.flatMap(b =>
      TopicProvisioning
        .topicsToRender(spec, broker, observed)
        .map(t => Action.EnsureKafkaTopic(StrimziRendering.topic(spec.projectId, t, b)))
    )
    val next = TopicProvisioning.status(spec, broker, observed)
    topics ++ Option.unless(current.contains(next))(
      Action.SetProjectStatus(ref.namespace, ref.name, next)
    )
  def apply(client: KubernetesClient, settings: Settings): ProjectReconciler =
    new ProjectReconciler(client, settings, new Fabric8Executor(client))
