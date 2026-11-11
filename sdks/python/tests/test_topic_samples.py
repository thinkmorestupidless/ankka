"""The documentation's topic samples (docs/build/topics.md): a view whose source is a topic."""

# docs:start topic-view
from dataclasses import dataclass, replace

from ankka import json_codec
from ankka.effects.view import ViewEffect
from ankka.view import View


@dataclass(frozen=True)
class StockEvent:
    productId: str
    delta: int


@dataclass(frozen=True)
class StockRow:
    productId: str
    level: int = 0


class StockLevels(View[StockEvent, StockRow]):
    component_id = "stock-levels"
    topic = "stock-events"
    event_codec = json_codec(StockEvent, "stock-event")
    row_codec = json_codec(StockRow, "stock-row")

    def on_change(self, event: StockEvent) -> ViewEffect:
        current = self.row or StockRow(self.metadata.subject or "")
        return self.effects.update_row(replace(current, level=current.level + event.delta))
# docs:end topic-view


from ankka.testkit import ViewTestKit


def test_a_products_level_is_the_sum_of_the_topics_messages_about_it() -> None:
    kit = ViewTestKit.of(StockLevels)
    kit.on_change("p1", StockEvent("p1", 5))
    kit.on_change("p1", StockEvent("p1", -2))
    kit.on_change("p2", StockEvent("p2", 1))
    assert kit.get("p1") == StockRow("p1", 3)
    assert kit.get("p2") == StockRow("p2", 1)
    assert kit.get("p3") is None


def test_the_view_declares_the_topic_as_its_source() -> None:
    assert StockLevels.to_component().view.source.topic == "stock-events"
