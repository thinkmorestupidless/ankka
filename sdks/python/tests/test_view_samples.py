"""The documentation's view samples (docs/build/views.md): a keyed view declaring its version."""

from __future__ import annotations

from ankka import json_codec
from ankka.effects.keyed_view import KeyedViewEffect
from ankka.keyed_view import KeyedView, on
from tests.test_views import Customer, Note, ShipmentRow


class Shipments(KeyedView[ShipmentRow]):
    # docs:start version
    component_id = "shipments"
    row_codec = json_codec(ShipmentRow, "shipment-row")
    version = 2
    # docs:end version

    @on(Customer, Customer.event_codec)
    async def on_customer(self, event: Note) -> KeyedViewEffect:
        return self.effects.update_row(self.subject, ShipmentRow(self.subject, self.subject, (event.text,)))


def test_a_raised_version_is_declared_to_the_runtime() -> None:
    assert Shipments.to_component().view.version == 2
