"""The publishing consumer, with nothing started: the kit hands it a change and gives back what it
would publish, read back from the bytes that would be on the topic."""

from __future__ import annotations

from ankka.effects.consumer import Ignore
from ankka.testkit import ConsumerTestKit

from examples.shopping_cart.checkout_notices import CheckoutNotice, CheckoutNoticePublisher
from examples.shopping_cart.domain import CheckedOut, ItemAdded, LineItem


def test_only_a_checkout_leaves_the_service_keyed_by_the_cart() -> None:
    kit = ConsumerTestKit.of(CheckoutNoticePublisher)
    assert isinstance(kit.on_message(ItemAdded(LineItem("p1", "Pen", 2)), "c1", sequence=1), Ignore)
    assert kit.messages == []
    kit.on_message(CheckedOut(), "c1", sequence=2)
    (notice,) = kit.messages
    assert isinstance(notice.payload, CheckoutNotice) and notice.payload.cartId == "c1" and notice.payload.at > 0
    # No key of its own: the message is keyed by its subject, the cart's id.
    assert notice.key is None
    assert CheckoutNoticePublisher.to_component().consumer.produces_to == "cart-checkouts"
