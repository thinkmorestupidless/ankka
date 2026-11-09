"""The documentation's object storage samples (docs/build/object-storage.md): an entity that records a
photo's name and never its bytes, and the kit's store in memory."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

from ankka import DONE, Acl, Done, Endpoint, EventSourcedEffect, EventSourcedEntity, HttpProblem, ReadOnlyEffect, command, get, json_codec, query
from ankka.storage import InMemoryBuckets
from ankka.testkit import EndpointTestKit, EventSourcedTestKit


# docs:start listing
@dataclass(frozen=True)
class PhotoRecorded:
    """What a listing records about a photo: its name in the bucket, never its bytes."""

    name: str


@dataclass(frozen=True)
class ListingState:
    photos: list[str]


class Listing(EventSourcedEntity[ListingState, PhotoRecorded]):
    component_id = "listing"
    state_codec = json_codec(ListingState, "listing")
    event_codec = json_codec(PhotoRecorded, "photo-recorded")

    def empty_state(self) -> ListingState:
        return ListingState([])

    def apply_event(self, state: ListingState, event: PhotoRecorded) -> ListingState:
        return ListingState([*state.photos, event.name])

    @command("record-photo")
    def record_photo(self, name: str) -> EventSourcedEffect[ListingState, PhotoRecorded, Done]:
        return self.effects.persist(PhotoRecorded(name)).then_reply(lambda _: DONE)

    @query("photos")
    def photos(self) -> ReadOnlyEffect[ListingState, PhotoRecorded, ListingState]:
        return self.effects.reply(self.state)
# docs:end listing


class PhotosEndpoint(Endpoint):
    """Describes a photo kept in the `photos` bucket: its content type, or 404."""

    prefix = "/photos"
    acl = Acl.ALLOW_ALL

    @get("/{name}/type")
    async def content_type(self, name: str) -> str:
        info = await self.storage.bucket("photos").stat(name)
        if info is None:
            raise HttpProblem(404, f"no photo '{name}'")
        return info.content_type


def test_a_listing_records_names_and_never_bytes() -> None:
    kit = EventSourcedTestKit.of(Listing, "l-42")
    assert kit.call("record-photo", "42/a.png").events == (PhotoRecorded("42/a.png"),)
    assert kit.call("photos").reply == ListingState(["42/a.png"])


def test_the_kit_holds_the_store_in_memory() -> None:
    # docs:start kit-storage
    store = InMemoryBuckets({"photos": "public"})
    kit = EndpointTestKit.of(PhotosEndpoint, storage=store)
    asyncio.run(kit.storage.bucket("photos").put("b.png", b"png", content_type="image/png"))
    assert kit.get("/photos/b.png/type").text() == "image/png"
    assert kit.storage.bucket("photos").url("b.png").endswith("/b.png")
    # docs:end kit-storage
    assert kit.get("/photos/nope/type").status == 404
