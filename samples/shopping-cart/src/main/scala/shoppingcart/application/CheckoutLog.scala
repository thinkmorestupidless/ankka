package shoppingcart.application

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import shoppingcart.domain.CheckoutRecord

/**
 * Where the notifier records checkouts: a key value entity per cart holding when it happened.
 *
 * Nothing needs to know how the record came to be — only what it is now — so this stores the latest
 * value rather than a journal of events. The cart itself is the opposite case, and is event
 * sourced.
 */
// docs:start key-value
final class CheckoutLog(context: KeyValueEntityContext) extends KeyValueEntity[CheckoutRecord]:

  private val cartId: String = context.entityId

  def emptyState: CheckoutRecord = CheckoutRecord(cartId)

  def record(at: Long): Effect[Done] =
    effects.updateState(CheckoutRecord(cartId, at, notified = true)).thenReply(_ => Done)

  def get: ReadOnlyEffect[CheckoutRecord] = effects.reply(currentState)

object CheckoutLog
    extends KeyValueEntity.Companion[CheckoutLog, CheckoutRecord](
      componentId = ComponentId("checkout-log"),
      stateSerializer = Codecs.serializer[CheckoutRecord]("checkout-record")
    ):
  def create(context: KeyValueEntityContext) = new CheckoutLog(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)
// docs:end key-value
