"""Where the notifier records checkouts: a key value entity per cart holding when it happened."""

from __future__ import annotations

from dataclasses import dataclass

from ankka import DONE, Done, json_codec
from ankka.effects.key_value import KeyValueEffect, KeyValueReadOnlyEffect
from ankka.event_sourced_entity import command, query
from ankka.key_value_entity import KeyValueEntity


@dataclass(frozen=True)
class CheckoutRecord:
    cartId: str
    at: int = 0
    notified: bool = False


class CheckoutLog(KeyValueEntity[CheckoutRecord]):
    component_id = "checkout-log"
    state_codec = json_codec(CheckoutRecord, "checkout-record")

    def empty_state(self) -> CheckoutRecord:
        return CheckoutRecord(self.entity_id)

    @command("record")
    def record(self, at: int) -> KeyValueEffect[CheckoutRecord, Done]:
        return self.effects.update_state(CheckoutRecord(self.entity_id, at, True)).then_reply(lambda _: DONE)

    @query("get")
    def get(self) -> KeyValueReadOnlyEffect[CheckoutRecord, CheckoutRecord]:
        return self.effects.reply(self.state)
