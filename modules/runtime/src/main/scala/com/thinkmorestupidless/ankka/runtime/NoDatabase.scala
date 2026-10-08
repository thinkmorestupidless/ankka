package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentKind}
import com.thinkmorestupidless.ankka.sdk.{DeferredCall, TimerScheduler}
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

/**
 * A service that declares it has no database (feature 037): `ANKKA_DATABASE=none`, which the
 * operator sets for a descriptor saying `"database": "none"`. Such a service is made of consumers,
 * endpoints and agents; an entity, a view, a workflow or a timed action needs a database and is
 * refused at start, naming itself. Nothing opens a connection: the secret store is unavailable, the
 * projections open none for consumers alone, and the timer scheduler refuses.
 */
object NoDatabase:

  val EnvVar: String = "ANKKA_DATABASE"

  def declared(config: Config): Boolean =
    config.hasPath("ankka.database") && config.getString("ankka.database").trim == "none"

  private val NeedsOne: Set[ComponentKind] = Set(
    ComponentKind.EventSourcedEntity,
    ComponentKind.KeyValueEntity,
    ComponentKind.Workflow,
    ComponentKind.View,
    ComponentKind.TimedAction
  )

  /** Every component that needs a database, named. */
  def problems(descriptors: Seq[ComponentDescriptor]): Vector[String] =
    descriptors.toVector.filter(d => NeedsOne(d.kind)).map { d =>
      val kind = d.kind match
        case ComponentKind.EventSourcedEntity => "event sourced entity"
        case ComponentKind.KeyValueEntity     => "key value entity"
        case ComponentKind.Workflow           => "workflow"
        case ComponentKind.View               => "view"
        case ComponentKind.TimedAction        => "timed action"
        case other                            => other.toString.toLowerCase
      s"$kind '${d.componentId}' needs a database, and this service declares none ($EnvVar=none)"
    }

  /** The scheduler of a service with no database: every timer is refused. */
  object UnavailableScheduler extends TimerScheduler:
    private def refuse(name: String): Nothing =
      throw IllegalStateException(
        s"timer '$name' cannot be scheduled: this service declares no database ($EnvVar=none)"
      )
    def createSingleTimer(name: String, delay: FiniteDuration, call: DeferredCall): Unit =
      refuse(name)
    def createRecurringTimer(
        name: String,
        delay: FiniteDuration,
        period: FiniteDuration,
        call: DeferredCall
    ): Unit = refuse(name)
    def delete(name: String): Unit    = refuse(name)
    def exists(name: String): Boolean = refuse(name)
