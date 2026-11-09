"""The documentation's unit kit samples (docs/build/testing.md): a key value entity in its kit."""

from __future__ import annotations

from ankka.testkit import KeyValueTestKit
from tests.kinds import Profile, ProfileEntity


def test_a_key_value_kit_shows_whether_the_state_changed() -> None:
    # docs:start key-value-kit
    kit = KeyValueTestKit.of(ProfileEntity, "user-1")
    result = kit.call("set", "Ada")
    assert result.changed
    assert kit.state == Profile("Ada", 0)
    # docs:end key-value-kit
    refused = kit.call("set", "")
    assert not refused.changed and kit.state == Profile("Ada", 0)
