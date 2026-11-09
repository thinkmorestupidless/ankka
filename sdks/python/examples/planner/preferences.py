"""A user's stated preferences, so an agent can be given context it did not ask for."""

from __future__ import annotations

from dataclasses import replace

from ankka import DONE, Done, command, json_codec, query
from ankka.effects.key_value import KeyValueEffect, KeyValueReadOnlyEffect
from ankka.key_value_entity import KeyValueEntity

from examples.planner.domain import Preferences


class PreferencesEntity(KeyValueEntity[Preferences]):
    component_id = "preferences"
    state_codec = json_codec(Preferences, "preferences")

    def empty_state(self) -> Preferences:
        return Preferences(self.entity_id)

    @command("set")
    def set(self, preferences: Preferences) -> KeyValueEffect[Preferences, Done]:
        if preferences.maxBudget < 0:
            return self.effects.error("budget cannot be negative")
        return self.effects.update_state(replace(preferences, userId=self.entity_id)).then_reply(lambda _: DONE)

    @command("add-like")
    def add_like(self, activity: str) -> KeyValueEffect[Preferences, Preferences]:
        if not activity.strip():
            return self.effects.error("an activity needs a name")
        likes = self.state.likes if activity in self.state.likes else [*self.state.likes, activity]
        return self.effects.update_state(replace(self.state, likes=likes)).then_reply_state()

    @query("get")
    def get(self) -> KeyValueReadOnlyEffect[Preferences, Preferences]:
        return self.effects.reply(self.state)
