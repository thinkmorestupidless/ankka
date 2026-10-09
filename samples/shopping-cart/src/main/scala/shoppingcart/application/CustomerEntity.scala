package shoppingcart.application

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.core.personal.Personal
import com.thinkmorestupidless.ankka.sdk.*

/** What a customer gives for a cart: plain text, as it arrives over HTTP. */
final case class CustomerDetails(name: String, email: String)

// docs:start personal-fields
/**
 * The customer a cart belongs to. The name and email are personal fields of the data subject
 * `customer/<cartId>`: written encrypted under that customer's key, and read as erased everywhere
 * once the customer is erased — in the state, its backups, and every copy of them.
 */
final case class Customer(name: Personal[String], email: Personal[String])
// docs:end personal-fields

/**
 * The cart's customer, kept apart from the cart: the cart's events are the shared journal every
 * language's sample writes, and its customer is this service's alone.
 */
final class CustomerEntity(context: KeyValueEntityContext) extends KeyValueEntity[Option[Customer]]:
  def emptyState: Option[Customer] = None

  private def subject = s"customer/${context.entityId}"

  // docs:start personal-write
  def setDetails(details: CustomerDetails): Effect[CustomerDetails] =
    val customer = Customer(
      Personal.present(subject, details.name),
      Personal.present(subject, details.email)
    )
    effects.updateState(Some(customer)).thenReply(_ => details)
  // docs:end personal-write

  // docs:start personal-read
  /** The details, each field the value or "erased": the erased case has to be handled. */
  def getDetails: ReadOnlyEffect[CustomerDetails] =
    effects.reply(
      currentState.fold(CustomerDetails("", ""))(c =>
        CustomerDetails(c.name.getOrElse("erased"), c.email.getOrElse("erased"))
      )
    )
  // docs:end personal-read

object CustomerEntity
    extends KeyValueEntity.Companion[CustomerEntity, Option[Customer]](
      componentId = ComponentId("customer"),
      stateSerializer = Codecs.serializer[Option[Customer]]("customer")
    ):
  given Serializer[CustomerDetails] = Codecs.serializer[CustomerDetails]("customer-details")
  def create(context: KeyValueEntityContext) = new CustomerEntity(context)
  val setDetails                             = command("set-details")(_.setDetails)
  val getDetails                             = query("get-details")(_.getDetails)
