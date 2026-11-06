"""The integration kit's keyring and personal value assertion (feature 042, T071): a personal field
written through the real sidecar is in no table as written, an erasure through ``kit.keyring`` reads it
back as erased, and an outage refuses a key the sidecar does not hold."""

from __future__ import annotations

from dataclasses import dataclass

import pytest

from ankka import Ankka, ErrorCode, EventSourcedEffect, EventSourcedEntity, ReadOnlyEffect, command, json_codec, query
from ankka.client import CommandError
from ankka.personal import Personal
from ankka.testkit.integration import personal_value_failure, personal_value_query


@dataclass(frozen=True)
class Joined:
    email: Personal[str]


@dataclass(frozen=True)
class Member:
    email: Personal[str] | None = None


class MemberEntity(EventSourcedEntity[Member, Joined]):
    component_id = "member"
    state_codec = json_codec(Member, "member")
    event_codec = json_codec(Joined, "member-joined")

    def empty_state(self) -> Member:
        return Member()

    def apply_event(self, state: Member, event: Joined) -> Member:
        return Member(event.email)

    @command("join")
    def join(self, email: str) -> EventSourcedEffect[Member, Joined, str]:
        return self.effects.persist(Joined(Personal.of(f"member/{self.entity_id}", email))).then_reply(lambda _: "joined")

    @query("email")
    def email(self) -> ReadOnlyEffect[Member, Joined, str]:
        return self.effects.reply("none" if self.state.email is None else self.state.email.or_else("erased"))


def test_the_query_names_each_value_form_and_place() -> None:
    sql = personal_value_query(("ada@example.com", "o'neil"))
    # The JVM kit's forms: unpadded base64 and base64url, lower-case hex, quotes doubled.
    assert "'YWRhQGV4YW1wbGUuY29t'" in sql and "'616461406578616d706c652e636f6d'" in sql
    assert "'o''neil'" in sql
    failure = personal_value_failure(("ada@example.com",), "0 base64 public.event_journal.event_payload\n")
    assert failure == "a personal value can be read back from what the service kept:\n  'ada@example.com' as base64 in public.event_journal.event_payload"
    assert personal_value_failure(("ada@example.com",), "") is None


@pytest.mark.slow
async def test_a_personal_field_is_sealed_erased_and_refused_in_an_outage_through_the_sidecar() -> None:
    from ankka.testkit.integration import AnkkaTestKit

    service = Ankka.service().register(MemberEntity)
    async with await AnkkaTestKit.start(service) as kit:
        member = kit.client.for_event_sourced_entity("member", "8c1f")
        assert await member.call("join").invoke("ada@example.com", reply=str) == "joined"
        await kit.assert_no_personal_value("ada@example.com")
        # The assertion can fail: the envelope names its data subject in the clear, so asking for
        # the subject finds it in the journal.
        with pytest.raises(AssertionError, match="event_journal"):
            await kit.assert_no_personal_value("member/8c1f")

        # An outage refuses a key the sidecar has not fetched, and an erasure, as unavailable.
        await kit.keyring.outage(600)
        with pytest.raises(CommandError) as refused:
            await kit.client.for_event_sourced_entity("member", "9d2e").call("join").invoke("grace@example.com", reply=str)
        assert refused.value.error.code == ErrorCode.UNAVAILABLE, refused.value.error
        with pytest.raises(RuntimeError, match="503"):
            await kit.keyring.erase("member/8c1f")
        await kit.keyring.recover()

        seq = await kit.keyring.erase("member/8c1f")
        assert seq >= 1
        await kit.restart()
        member = kit.client.for_event_sourced_entity("member", "8c1f")
        assert await member.call("email").invoke(reply=str) == "erased"
        await kit.assert_no_personal_value("ada@example.com", "grace@example.com")
