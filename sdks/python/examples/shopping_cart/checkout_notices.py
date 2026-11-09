"""A consumer that publishes onward to a topic. Not registered by ``main.py``: a sidecar with no
broker refuses a service that publishes to a topic, so the cart runs as it is without one."""

from __future__ import annotations

import time
from dataclasses import dataclass

from ankka import json_codec
from ankka.consumer import Consumer
from ankka.effects.consumer import ConsumerEffect

from examples.shopping_cart.domain import CheckedOut, ShoppingCartEvent
from examples.shopping_cart.entity import ShoppingCartEntity


# docs:start publishing-consumer
@dataclass(frozen=True)
class CheckoutNotice:
    """What leaves the service when a cart is checked out."""

    cartId: str
    at: int


class CheckoutNoticePublisher(Consumer[ShoppingCartEvent, CheckoutNotice]):
    """Turns an internal event into a published one.

    The cart's own event type is an implementation detail; this consumer decides which events are
    worth telling the outside world about and in what shape, so the domain stays free to change
    without breaking downstream consumers."""

    component_id = "checkout-notice-publisher"
    source = ShoppingCartEntity
    message_codec = ShoppingCartEntity.event_codec
    produces_to = "cart-checkouts"
    out_codec = json_codec(CheckoutNotice, "checkout-notice")

    def on_message(self, event: ShoppingCartEvent) -> ConsumerEffect:
        if not isinstance(event, CheckedOut):
            return self.effects.ignore()
        return self.effects.produce(CheckoutNotice(self.metadata.subject or "", int(time.time() * 1000)))
# docs:end publishing-consumer
