package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{
  ComponentDescriptor,
  ComponentId,
  ComponentKind,
  Contract
}
import com.thinkmorestupidless.ankka.runtime.remote.{
  RemoteConsumerDescriptor,
  RemoteKeyedViewDescriptor,
  RemoteSource,
  RemoteViewDescriptor
}
import com.thinkmorestupidless.ankka.sdk.{
  ChangeSource,
  ConsumerDescriptor,
  KeyedViewDescriptor,
  Publication,
  ViewDescriptor
}

/** What a view or a consumer declared it reads: one of three things, in either language. */
private[runtime] enum DeclaredSource:
  /** Every event of an event sourced entity. */
  case Events(component: ComponentId)

  /** The state of a key value entity, as it changes. */
  case State(component: ComponentId)

  /** A topic on the broker. */
  /** A topic, with the contract the component states for it and the declared broker it names. */
  case Topic(name: String, contract: Option[Contract] = None, broker: Option[String] = None)

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
   * Everything `descriptor` reads that has a change stream, when it is a view or a consumer: one
   * source for a plain view or a consumer, every source for a keyed view.
   */
  def sourcesOf(descriptor: ComponentDescriptor): Vector[DeclaredSource] = descriptor match
    case view: ViewDescriptor[?, ?, ?]         => Vector(of(view.source))
    case consumer: ConsumerDescriptor[?, ?, ?] => Vector(of(consumer.source))
    case view: KeyedViewDescriptor[?, ?]       => view.sources.map(s => of(s.source))
    case view: RemoteViewDescriptor            => of(view.source).toVector
    case consumer: RemoteConsumerDescriptor    => of(consumer.source).toVector
    case view: RemoteKeyedViewDescriptor       => view.sources.flatMap(of)
    case _                                     => Vector.empty

  /** What a plain view or a consumer reads: its one source, when that has a change stream. */
  def sourceOf(descriptor: ComponentDescriptor): Option[DeclaredSource] =
    sourcesOf(descriptor).headOption

  /** The topic `descriptor` publishes to, when it is a consumer that publishes. */
  def destinationOf(descriptor: ComponentDescriptor): Option[String] =
    publicationOf(descriptor).map(_.topic)

  /** What a consumer publishes to, with its contract and broker. */
  def publicationOf(descriptor: ComponentDescriptor): Option[Publication] = descriptor match
    case consumer: ConsumerDescriptor[?, ?, ?] =>
      consumer.produces.orElse(consumer.produceTo.map(Publication(_)))
    case consumer: RemoteConsumerDescriptor => consumer.publication
    case _                                  => None

  private def of(source: ChangeSource[?]): DeclaredSource = source match
    case ChangeSource.EventSourced(component, _) => DeclaredSource.Events(component)
    case ChangeSource.KeyValue(component, _)     => DeclaredSource.State(component)
    case ChangeSource.Topic(topic, _, _, options) =>
      DeclaredSource.Topic(topic, options.contract, options.broker)

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
    case RemoteSource.Topic(name, _, options) =>
      Some(DeclaredSource.Topic(name, options.contract, options.broker))
