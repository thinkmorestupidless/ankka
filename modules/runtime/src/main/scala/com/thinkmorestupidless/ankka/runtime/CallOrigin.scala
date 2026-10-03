package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{ComponentRegistry, Metadata}

/**
 * Where a call came from: the component that made it, and the handler in that component.
 *
 * A span says which trace a call belongs to. This says who made it, in names: a component's id and
 * the handler that was running, or for an endpoint its id in the topology and the route. A host
 * sets it on the thread for as long as its handler runs (`Trace.within`), the transport writes it
 * into a call's metadata, and the host that takes the call reads it back. It is carried by name
 * because a name is the one thing both ends of a call agree on: the span that made the call may be
 * on another node, and an interned id means something only in the process that interned it.
 *
 * Called an origin, and not a caller, because `http` already has a `Caller`: who a request is from,
 * read off a certificate. On the wire it is `ankka-caller`, which is what it is to whoever reads a
 * call's metadata.
 */
final case class CallOrigin(component: String, handler: String)

object CallOrigin:

  val MetadataKey: String = "ankka-caller"

  /**
   * The local console, reading an entity or a session. Marked so that it can be left out: looking
   * at a service must not change what its topology says it does. No component can be this, since a
   * component id cannot start with a bracket, and it is not a declared name, so nothing a process
   * sends can pass for it: only a thread of this runtime can set it.
   */
  val Console: CallOrigin = CallOrigin("(console)", "read")

  def encode(origin: CallOrigin): String = s"${origin.component}#${origin.handler}"

  /**
   * Split on the first `#`: a component id cannot hold one, and a handler may (a route is a
   * handler, and a path is whatever a developer wrote).
   */
  def decode(value: String): Option[CallOrigin] =
    val at = value.indexOf('#')
    Option.when(at > 0 && at < value.length - 1)(
      CallOrigin(value.substring(0, at), value.substring(at + 1))
    )

  def into(metadata: Metadata, origin: CallOrigin): Metadata =
    metadata.set(MetadataKey, encode(origin))

  /**
   * Takes an origin out of metadata, so one that was forwarded does not outlive the call it named.
   */
  def strip(metadata: Metadata): Metadata =
    if metadata.contains(MetadataKey) then metadata.remove(MetadataKey) else metadata

  /** What the metadata says, as it says it: not yet checked against anything. */
  def from(metadata: Metadata): Option[CallOrigin] = metadata.get(MetadataKey).flatMap(decode)

/**
 * Every component and handler name this service declared, and nothing else.
 *
 * A name in a call's metadata is whatever was sent. From a handler in this process it is the
 * handler's own; from a process in another language it is a string that process forwarded. So a
 * name is taken at its word only when it is one of these: a registered component and a handler it
 * declares, or an endpoint and a route it serves. Anything else is no name at all, and the call is
 * from an unknown origin or to an undeclared handler. That is what keeps the recorder's table of
 * names the size of what was registered, whatever is sent to the service.
 */
final class DeclaredNames private (known: Set[(String, String)], components: Set[String]):

  def validate(component: String, handler: String): Boolean = known.contains((component, handler))

  /** Whether a component of this id is registered, whatever handler was named. */
  def registered(component: String): Boolean = components.contains(component)

  /** The origin a call's metadata names, when it is one this service declared. */
  def origin(metadata: Metadata): Option[CallOrigin] =
    CallOrigin.from(metadata).filter(o => validate(o.component, o.handler))

  def size: Int = known.size

  /** How many different names there are to intern: what the recorder's table can grow to. */
  def names: Int =
    known.iterator.flatMap((component, handler) => Iterator(component, handler)).toSet.size

object DeclaredNames:

  /** Before a service has said what it is made of: nothing is declared, so nothing is believed. */
  val none: DeclaredNames = new DeclaredNames(Set.empty, Set.empty)

  /**
   * @param routes
   *   what the service's endpoints serve. An endpoint is named as the topology names it, and its
   *   handlers are its routes, each by its method and its whole path as a template.
   */
  def of(registry: ComponentRegistry, routes: Vector[ServedRoute]): DeclaredNames =
    val declared = registry.components.flatMap { descriptor =>
      descriptor.declaredHandlers.map(handler => descriptor.componentId.toString -> handler.name)
    }
    val served = routes.map(route => route.endpoint -> routeName(route))
    new DeclaredNames(
      (declared ++ served).toSet,
      registry.components.map(_.componentId.toString).toSet
    )

  /** A route as a handler's name: `POST /carts/{cartId}/items`. */
  def routeName(route: ServedRoute): String = s"${route.method} ${route.path}"
