package com.thinkmorestupidless.ankka.testkit.topology

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, PathTemplate}
import com.thinkmorestupidless.ankka.sdk.*

// The components a topology scenario is built from. A step names a component ("an event sourced
// entity "cart""), so each kind here is made with the id it is given: what a scenario says about
// the service is all there is to the service.

/** The one event a kit entity records. */
final case class Noted(note: String)

/** The one row a kit view keeps. */
final case class Seen(changes: Int)

final class KitEventSourcedEntity extends EventSourcedEntity[Int, Noted]:
  def emptyState: Int                 = 0
  def applyEvent(event: Noted): Int   = currentState + 1
  def note(text: String): Effect[Int] = effects.persist(Noted(text)).thenReply(identity)
  def count: ReadOnlyEffect[Int]      = effects.reply(currentState)

final class EventSourcedKit(id: String)
    extends EventSourcedEntity.Companion[KitEventSourcedEntity, Int, Noted](
      componentId = ComponentId(id),
      stateSerializer = Serializers.int,
      eventSerializer = Kit.noted
    ):
  def create(context: EventSourcedEntityContext) = new KitEventSourcedEntity
  val note                                       = command("note")(_.note)
  val count                                      = query("count")(_.count)

final class KitKeyValueEntity extends KeyValueEntity[Int]:
  def emptyState: Int          = 0
  def bump: Effect[Int]        = effects.updateState(currentState + 1).thenReplyState
  def get: ReadOnlyEffect[Int] = effects.reply(currentState)

final class KeyValueKit(id: String)
    extends KeyValueEntity.Companion[KitKeyValueEntity, Int](
      componentId = ComponentId(id),
      stateSerializer = Serializers.int
    ):
  def create(context: KeyValueEntityContext) = new KitKeyValueEntity
  val bump                                   = command("bump")(_.bump)
  val get                                    = query("get")(_.get)

/** A view over whatever it is told to read: it counts the changes it was given. */
final class KitView[Src] extends View[Src, Seen]:
  def onChange(change: Src): Effect =
    effects.updateRow(Seen(rowState.fold(1)(_.changes + 1)))

final class ViewKit[Src](id: String, source: ChangeSource[Src])
    extends View.Companion[KitView[Src], Src, Seen](
      componentId = ComponentId(id),
      source = source,
      rowSerializer = Kit.seen
    ):
  def create(context: ViewComponentContext) = new KitView[Src]

/**
 * A consumer over whatever it is told to read, publishing a line for each message when asked to.
 */
final class KitConsumer[Src](publishes: Boolean) extends Consumer[Src, String]:
  def onMessage(message: Src): Effect =
    if publishes then effects.produce(message.toString) else effects.ignore()

final class ConsumerKit[Src](id: String, source: ChangeSource[Src], publishesTo: Option[String])
    extends Consumer.Companion[KitConsumer[Src], Src, String](
      componentId = ComponentId(id),
      source = source
    ):
  def create(context: ConsumerContext) = new KitConsumer[Src](publishesTo.isDefined)
  override val outputSerializer: Option[Serializer[String]] =
    publishesTo.map(_ => Serializers.string)
  override val produceTo: Option[String] = publishesTo

/**
 * An endpoint serving the routes it is given, each as a scenario writes it (`GET /status/health`).
 *
 * Its prefix is the route's first segment, as a developer's endpoint for `/carts` would have, so a
 * scenario's routes and the endpoint's are the same words.
 */
final class KitEndpoint(prefix: String, routes: Vector[KitEndpoint.Route])
    extends HttpEndpoint(prefix):
  val acl: Acl = Acl.AllowAll

  routes.foreach { route =>
    (route.method, PathTemplate.parse(route.template).arity) match
      case ("GET", 0)  => get(route.template)(() => route.serve(Vector.empty))
      case ("GET", 1)  => get(route.template)((a: String) => route.serve(Vector(a)))
      case ("POST", 0) => post(route.template)(() => route.serve(Vector.empty))
      case ("POST", 1) => post(route.template)((a: String) => route.serve(Vector(a)))
      case (method, arity) =>
        throw IllegalArgumentException(
          s"the kit's endpoint serves GET and POST with at most one path parameter, " +
            s"not $method with $arity"
        )
  }

object KitEndpoint:

  /** One route, and what serving it does with the path's parameters. */
  final case class Route(method: String, template: String, serve: Vector[String] => String)

  /** `"POST /carts/{cartId}/items"` as the prefix an endpoint is registered with and the rest. */
  def split(route: String): (String, String, String) =
    route.split(" ", 2) match
      case Array(method, path) =>
        path.split("/").filter(_.nonEmpty).toList match
          case first :: rest if rest.nonEmpty =>
            (method, s"/$first", rest.mkString("/", "/", ""))
          case _ =>
            throw IllegalArgumentException(
              s"'$route': a kit route has a prefix and at least one more segment, as in " +
                "\"GET /status/health\""
            )
      case _ => throw IllegalArgumentException(s"'$route' is not a method and a path")

object Kit:
  val noted: Serializer[Noted] = Codecs.serializer[Noted]("kit-noted")
  val seen: Serializer[Seen]   = Codecs.serializer[Seen]("kit-seen")

  def eventsOf(entity: String): ChangeSource[Noted] =
    ChangeSource.EventSourced(ComponentId(entity), noted)

  def stateOf(entity: String): ChangeSource[Int] =
    ChangeSource.KeyValue(ComponentId(entity), Serializers.int)

  def topic(name: String): ChangeSource[String] =
    ChangeSource.Topic(name, Serializers.string)
