package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

/** A key value entity to record spans with: a command that answers, and one that refuses. */
final class CartEntity extends KeyValueEntity[String]:
  def emptyState: String = ""

  def addItem(item: String): Effect[String] =
    if item == "refuse" then effects.error(s"'$item' is not for sale", ErrorCode.Conflict)
    else effects.updateState(currentState + item).thenReply(identity)

object CartEntity
    extends KeyValueEntity.Companion[CartEntity, String](
      componentId = ComponentId("cart"),
      stateSerializer = Codecs.serializer[String]("cart")
    ):
  def create(context: KeyValueEntityContext) = new CartEntity
  val addItem                                = command("add-item")(_.addItem)
