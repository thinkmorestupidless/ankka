"""Turns an internal event into an action elsewhere: the cart's own events are an implementation
detail, and this consumer decides which are worth acting on. Here it records the checkout in
the checkout log through the client — a consumer that publishes to a topic instead declares
``produces_to`` and an ``out_codec``, and the sidecar needs a broker (``ANKKA_KAFKA_BOOTSTRAP_SERVERS``)."""

from __future__ import annotations

import time

from ankka import Done
from ankka.consumer import Consumer
from ankka.effects.consumer import ConsumerEffect

from examples.shopping_cart.domain import CheckedOut, ShoppingCartEvent
from examples.shopping_cart.entity import ShoppingCartEntity


class CheckoutNotifier(Consumer[ShoppingCartEvent, None]):
    component_id = "checkout-notifier"
    source = ShoppingCartEntity
    message_codec = ShoppingCartEntity.event_codec

    async def on_message(self, event: ShoppingCartEvent) -> ConsumerEffect:  # type: ignore[override]
        if not isinstance(event, CheckedOut):
            return self.effects.ignore()
        cart_id = self.metadata.subject or ""
        assert self.client is not None
        await self.client.for_key_value_entity("checkout-log", cart_id).call("record").invoke(int(time.time() * 1000), reply=Done)
        return self.effects.done()
