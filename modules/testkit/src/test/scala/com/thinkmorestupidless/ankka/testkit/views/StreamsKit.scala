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
