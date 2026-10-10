package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentKind}
import com.thinkmorestupidless.ankka.runtime.remote.{RemoteKeyedViewDescriptor, RemoteSource}
import com.thinkmorestupidless.ankka.sdk.{ChangeSource, KeyedViewDescriptor}

/**
 * What a keyed view may read (`contracts/keyed-views.md`, K1–K5), checked once for every keyed view
 * however it arrived: written in Scala, or discovered from a process or a module. A service that
 * breaks a rule does not start, and the problem names the view.
 *
 * A keyed view reads entities only. A topic is refused beside an entity because only the entity's
 * half could be rebuilt — a broker retains a window, not a record — and refused alone because a
 * topic source is already a plain view's.
 */
private[ankka] object KeyedViewRules:

  /** What one source is, as the rules see it: a topic, or a component of some kind. */
  private enum Read:
    case Topic(name: String)
    case Component(kind: ComponentKind, id: String)

  def problems(descriptors: Seq[ComponentDescriptor]): Vector[String] =
    descriptors.toVector.flatMap {
      case view: KeyedViewDescriptor[?, ?] =>
        problemsOf(
          view.componentId.toString,
          view.sources.map(_.source match
            case ChangeSource.EventSourced(id, _) =>
              Read.Component(ComponentKind.EventSourcedEntity, id.toString)
            case ChangeSource.KeyValue(id, _) =>
              Read.Component(ComponentKind.KeyValueEntity, id.toString)
            case ChangeSource.Workflow(id, _) =>
              Read.Component(ComponentKind.Workflow, id.toString)
            case ChangeSource.Topic(name, _, _, _) => Read.Topic(name))
        )
      case view: RemoteKeyedViewDescriptor =>
        problemsOf(
          view.componentId.toString,
          view.sources.map {
            case RemoteSource.Component(kind, id) => Read.Component(kind, id.toString)
            case RemoteSource.Topic(name, _, _)   => Read.Topic(name)
          }
        )
      case _ => Vector.empty
    }

  private def problemsOf(view: String, sources: Vector[Read]): Vector[String] =
    val topics   = sources.collect { case Read.Topic(name) => name }
    val entities = sources.collect { case c: Read.Component => c }
    val repeated = entities.groupBy(_.id).collect { case (id, same) if same.size > 1 => id }
    val notEntities = entities.filterNot(c =>
      c.kind == ComponentKind.EventSourcedEntity || c.kind == ComponentKind.KeyValueEntity ||
        c.kind == ComponentKind.Workflow
    )
    // What a topic was read beside, in the words a developer declared it with.
    val beside =
      if entities.nonEmpty && entities.forall(_.kind == ComponentKind.Workflow) then "a workflow"
      else "an entity"
    Vector(
      Option.when(sources.isEmpty)(
        s"view '$view' declares no source; a keyed view reads one or more"
      ),
      Option.when(topics.nonEmpty && entities.nonEmpty)(
        s"view '$view' reads the topic '${topics.head}' and $beside; a topic and $beside may " +
          "not be sources of one view"
      ),
      Option.when(topics.nonEmpty && entities.isEmpty)(
        s"view '$view' reads only topics ('${topics.head}'); a keyed view reads entities, and a " +
          "view of a topic is a plain view"
      )
    ).flatten ++
      repeated.toVector.sorted.map(id =>
        s"view '$view' reads '$id' twice; each source is read once"
      ) ++
      notEntities.map(c =>
        s"view '$view' reads ${c.kind} '${c.id}', which has no change stream; a keyed view reads " +
          "event sourced and key value entities and workflows"
      )
