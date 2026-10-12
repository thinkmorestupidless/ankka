package shoppingcart.application

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** The topic checkout notices are published to, and read from. */
object CheckoutTopic:

  /**
   * `cart-checkouts` unless `CART_CHECKOUTS_TOPIC` names another. A service names a topic by the
   * name its descriptor declares, and the platform's broker holds it under one that carries the
   * project; the variable is how two deployments of this one sample play two services of a project,
   * one publishing and one reading, under the topic names a test chooses.
   */
  val (project, name) = parse(
    sys.env.get("CART_CHECKOUTS_TOPIC").filter(_.trim.nonEmpty).getOrElse("cart-checkouts")
  )

  /**
   * Where the notices go: `CART_PUBLISH_TO` when it is set, else the topic they are read from when
   * that is this project's, else this project's `cart-checkouts`. Either variable may name another
   * project's topic as `<project>/<name>` (feature 040), which that project must grant.
   */
  val publishTo: Publication =
    val (p, n) = sys.env
      .get("CART_PUBLISH_TO")
      .filter(_.trim.nonEmpty)
      .map(parse)
      .getOrElse(if project.isEmpty then (None, name) else (None, "cart-checkouts"))
    Publication(n, project = p)

  /** `<project>/<name>` names another project's topic; a bare name is this project's. */
  def parse(value: String): (Option[String], String) = TopicAddress.split(value.trim)

/**
 * A checkout this service has read from the topic, and how many notices of it it has read: one,
 * unless a notice was read again, which is what a reader that starts over from the beginning does.
 */
final case class CheckoutSeen(cartId: String, at: Long, notices: Int)

/**
 * The checkouts read back from the topic they are published to: a service consuming a topic, which
 * may be another service's. Registered only when there is a broker to read from.
 */
final class CheckoutsSeenView extends View[CheckoutNotice, CheckoutSeen]:

  def onChange(notice: CheckoutNotice): Effect =
    val seen = rowState.fold(0)(_.notices)
    effects.updateRow(CheckoutSeen(notice.cartId, notice.at, seen + 1))

object CheckoutsSeen
    extends View.Companion[CheckoutsSeenView, CheckoutNotice, CheckoutSeen](
      componentId = ComponentId("checkouts-seen"),
      source = CheckoutTopic.project.fold(
        ChangeSource.fromTopic(
          CheckoutTopic.name,
          Codecs.serializer[CheckoutNotice]("checkout-notice")
        )
      )(p =>
        // docs:start cross-project-source
        // Another project's topic: the broker serves it while that project grants this service
        // consume on it, under this service's own consumer group.
        ChangeSource
          .fromTopic(p, CheckoutTopic.name, Codecs.serializer[CheckoutNotice]("checkout-notice"))
        // docs:end cross-project-source
      ),
      rowSerializer = Codecs.serializer[CheckoutSeen]("checkout-seen")
    ):
  def create(ctx: ViewComponentContext) = new CheckoutsSeenView
