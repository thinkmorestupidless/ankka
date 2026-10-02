package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentId, ComponentKind}
import com.thinkmorestupidless.ankka.runtime.remote.{
  RemoteConsumerDescriptor,
  RemoteSource,
  RemoteViewDescriptor
}
import com.thinkmorestupidless.ankka.sdk.{ChangeSource, ConsumerDescriptor, ViewDescriptor}

/** What a view or a consumer declared it reads: one of three things, in either language. */
private[runtime] enum DeclaredSource:
  /** Every event of an event sourced entity. */
  case Events(component: ComponentId)

  /** The state of a key value entity, as it changes. */
  case State(component: ComponentId)

  /** A topic on the broker. */
  case Topic(name: String)

/**
 * What a component declared it is connected to, when it was registered.
 *
 * A Scala view and a view in another language say the same thing in two types, `ChangeSource` and
 * `RemoteSource`. This is the one place that reads both, so the runtime that starts a projection
 * and the topology that draws it cannot come to read a descriptor differently, and a third kind of
 * descriptor is added here once.
 */
private[runtime] object DeclaredConnections:

  /**
   * What `descriptor` reads, when it is a view or a consumer and its source has a change stream.
   */
  def sourceOf(descriptor: ComponentDescriptor): Option[DeclaredSource] = descriptor match
    case view: ViewDescriptor[?, ?, ?]         => Some(of(view.source))
    case consumer: ConsumerDescriptor[?, ?, ?] => Some(of(consumer.source))
    case view: RemoteViewDescriptor            => of(view.source)
    case consumer: RemoteConsumerDescriptor    => of(consumer.source)
    case _                                     => None

  /** The topic `descriptor` publishes to, when it is a consumer that publishes. */
  def destinationOf(descriptor: ComponentDescriptor): Option[String] = descriptor match
    case consumer: ConsumerDescriptor[?, ?, ?] => consumer.produceTo
    case consumer: RemoteConsumerDescriptor    => consumer.producesTo
    case _                                     => None

  private def of(source: ChangeSource[?]): DeclaredSource = source match
    case ChangeSource.EventSourced(component, _) => DeclaredSource.Events(component)
    case ChangeSource.KeyValue(component, _)     => DeclaredSource.State(component)
    case ChangeSource.Topic(topic, _)            => DeclaredSource.Topic(topic)

  /**
   * Discovery lets a source name a component of any kind, and only an entity has a change stream.
   * Any other is no source at all: `ProjectionRuntime` refuses the service at startup, naming it.
   */
  private def of(source: RemoteSource): Option[DeclaredSource] = source match
    case RemoteSource.Component(ComponentKind.EventSourcedEntity, component) =>
      Some(DeclaredSource.Events(component))
    case RemoteSource.Component(ComponentKind.KeyValueEntity, component) =>
      Some(DeclaredSource.State(component))
    case RemoteSource.Component(_, _) => None
    case RemoteSource.Topic(name)     => Some(DeclaredSource.Topic(name))
