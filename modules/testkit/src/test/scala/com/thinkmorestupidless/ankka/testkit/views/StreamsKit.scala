package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Orders: what the view-stream suites read. A row is a number, so "the statement's order" is a fact
 * a test can check, and a customer, so a declared query has a value to take.
 */
final case class Order(customer: String, n: Int)

enum OrderEvent:
  case Placed(customer: String, n: Int)

final class OrderEntity extends EventSourcedEntity[Order, OrderEvent]:

  def emptyState: Order = Order("", 0)

  def applyEvent(event: OrderEvent): Order = event match
    case OrderEvent.Placed(customer, n) => Order(customer, n)

  def place(order: Order): Effect[Done] =
    effects.persist(OrderEvent.Placed(order.customer, order.n)).thenReply(_ => Done)

object OrderEntity
    extends EventSourcedEntity.Companion[OrderEntity, Order, OrderEvent](
      componentId = ComponentId("order"),
      stateSerializer = Codecs.serializer[Order]("order"),
      eventSerializer = Codecs.serializer[OrderEvent]("order-event")
    ):
  def create(context: EventSourcedEntityContext) = new OrderEntity

  val place = command("place")(_.place)

final case class OrderRow(orderId: String, customer: String, n: Int)

final class OrdersView extends View[OrderEvent, OrderRow]:
  def onChange(event: OrderEvent): Effect = event match
    case OrderEvent.Placed(customer, n) =>
      effects.updateRow(OrderRow(updateContext.subject, customer, n))

object Orders
    extends View.Companion[OrdersView, OrderEvent, OrderRow](
      componentId = ComponentId("orders"),
      source = ChangeSource.eventsOf(OrderEntity),
      rowSerializer = Codecs.serializer[OrderRow]("order-row")
    ):

  /** One customer's orders, by number. */
  val byCustomer = query("by-customer")(
    s"""SELECT row_key, payload FROM $table WHERE payload::jsonb->>'customer' = :customer
       |ORDER BY (payload::jsonb->>'n')::int""".stripMargin
  )

  /** Every order, each read after a pause: a statement the database cannot finish in time. */
  val allSlowly = query("all-slowly")(
    s"SELECT row_key, payload, pg_sleep(0.01) FROM $table ORDER BY (payload::jsonb->>'n')::int"
  )

  def create(ctx: ViewComponentContext) = new OrdersView

/** A cart: open until it is checked out, with a count of items so a write can change it. */
final case class Cart(items: Int, checkedOut: Boolean, dueAt: Long)

enum CartEvent:
  case Opened(dueAt: Long)
  case ItemAdded
  case CheckedOut
  case Touched

final class CartEntity extends EventSourcedEntity[Cart, CartEvent]:

  def emptyState: Cart = Cart(0, false, 0L)

  def applyEvent(event: CartEvent): Cart = event match
    case CartEvent.Opened(dueAt) => Cart(0, false, dueAt)
    case CartEvent.ItemAdded     => currentState.copy(items = currentState.items + 1)
    case CartEvent.CheckedOut    => currentState.copy(checkedOut = true)
    case CartEvent.Touched       => currentState

  def open(dueAt: Long): Effect[Done] =
    effects.persist(CartEvent.Opened(dueAt)).thenReply(_ => Done)
  def addItem(d: Done): Effect[Done]  = effects.persist(CartEvent.ItemAdded).thenReply(_ => d)
  def checkOut(d: Done): Effect[Done] = effects.persist(CartEvent.CheckedOut).thenReply(_ => d)
  def touch(d: Done): Effect[Done]    = effects.persist(CartEvent.Touched).thenReply(_ => d)
  def remove(d: Done): Effect[Done]   = effects.deleteEntity().thenReply(_ => d)

object CartEntity
    extends EventSourcedEntity.Companion[CartEntity, Cart, CartEvent](
      componentId = ComponentId("cart"),
      stateSerializer = Codecs.serializer[Cart]("cart"),
      eventSerializer = Codecs.serializer[CartEvent]("cart-event")
    ):
  def create(context: EventSourcedEntityContext) = new CartEntity

  val open     = command("open")(_.open)
  val addItem  = command("add-item")(_.addItem)
  val checkOut = command("check-out")(_.checkOut)
  val touch    = command("touch")(_.touch)
  val remove   = command("remove")(_.remove)

/** One row per cart. `version` counts the changes the row has seen, so order can be read off it. */
final case class CartRow(cartId: String, items: Int, checkedOut: Boolean, dueAt: Long, version: Int)

final class CartsView extends View[CartEvent, CartRow]:
  def onChange(event: CartEvent): Effect =
    val subject = updateContext.subject
    val before  = rowState.getOrElse(CartRow(subject, 0, false, 0L, 0))
    val after = event match
      case CartEvent.Opened(dueAt) => before.copy(dueAt = dueAt)
      case CartEvent.ItemAdded     => before.copy(items = before.items + 1)
      case CartEvent.CheckedOut    => before.copy(checkedOut = true)
      case CartEvent.Touched       => before
    effects.updateRow(after.copy(version = before.version + 1))

object Carts
    extends View.Companion[CartsView, CartEvent, CartRow](
      componentId = ComponentId("carts"),
      source = ChangeSource.eventsOf(CartEntity),
      rowSerializer = Codecs.serializer[CartRow]("cart-row")
    ):

  // docs:start watched-query
  /** The carts not checked out, watchable: the statement selects each row's key beside it. */
  val openCarts = query("open-carts")(
    s"SELECT row_key, payload FROM $table WHERE (payload::jsonb->>'checkedOut')::boolean = false"
  ).watched
  // docs:end watched-query

  /** The carts whose due time has passed: a match that depends on the time, not only the row. */
  val dueCarts = query("due-carts")(
    s"""SELECT row_key, payload FROM $table
       |WHERE (payload::jsonb->>'dueAt')::bigint > 0
       |AND (payload::jsonb->>'dueAt')::bigint <= (extract(epoch from now()) * 1000)::bigint""".stripMargin
  ).watched

  def create(ctx: ViewComponentContext) = new CartsView
