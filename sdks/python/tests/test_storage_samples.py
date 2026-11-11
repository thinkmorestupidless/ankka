"""The documentation's object storage samples (docs/build/object-storage.md): an entity that records a
photo's name and never its bytes, the endpoint over the buckets, and the kit's store in memory."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

from ankka import DONE, Acl, Done, Endpoint, EventSourcedEffect, EventSourcedEntity, HttpProblem, ReadOnlyEffect, command, get, json_codec, query
from ankka._proto.ankka.protocol.v1 import client_pb2, payload_pb2
from ankka.client import ComponentClient
from ankka.codec import DONE_CODEC, STRING
from ankka.storage import InMemoryBuckets
from ankka.testkit import EndpointTestKit, EventSourcedTestKit
from tests.photos_endpoint import KeepObject, RecordPhoto
from tests.photos_endpoint import PhotosEndpoint as StoragePhotos


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


class _Sidecar:
    """Records each call the endpoint makes and answers it done, as the listing would."""

    def __init__(self) -> None:
        self.calls: list[client_pb2.InvokeRequest] = []

    async def Invoke(self, request: client_pb2.InvokeRequest) -> client_pb2.InvokeReply:  # noqa: N802
        self.calls.append(request)
        payload = payload_pb2.Payload(content_type=DONE_CODEC.content_type, manifest=DONE_CODEC.manifest, data=b"")
        return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply(payload=payload))


def _photos() -> tuple[EndpointTestKit, _Sidecar]:
    sidecar = _Sidecar()
    client = ComponentClient(address="127.0.0.1:0")
    client._stub_cache = sidecar  # type: ignore[assignment]
    store = InMemoryBuckets({"photos": "public"})
    return EndpointTestKit.of(StoragePhotos, client, storage=store), sidecar


def test_the_photos_endpoint_keeps_reads_describes_and_lists() -> None:
    kit, _ = _photos()
    keep = KeepObject("42/a.txt", "hello", "text/plain")
    assert kit.post("/storage/objects", keep).status == 204
    assert kit.get("/storage/objects", query=[("name", "42/a.txt")]).text() == "hello"
    described = kit.get("/storage/stat", query=[("name", "42/a.txt")]).json()
    assert (described["name"], described["contentType"], described["length"]) == ("42/a.txt", "text/plain", 5)
    assert described["lastKept"]
    assert kit.post("/storage/objects", KeepObject("43/b.txt", "bye", "text/plain")).status == 204
    listed = kit.get("/storage/list", query=[("prefix", "42/")]).json()
    assert [o["name"] for o in listed["objects"]] == ["42/a.txt"]
    assert len(kit.get("/storage/list").json()["objects"]) == 2
    assert kit.get("/storage/objects", query=[("name", "nope")]).status == 404
    assert kit.get("/storage/stat", query=[("name", "nope")]).status == 404
    assert kit.get("/storage/objects").status == 400


def test_the_photos_endpoint_takes_an_upload_whole_and_deletes() -> None:
    kit, _ = _photos()
    png = b"\x89PNG\r\n\x1a\n"
    sent = kit.post("/storage/upload", png, query=[("name", "p.png"), ("bucket", "photos")], headers=[("Content-Type", "image/png")])
    assert sent.status == 204
    read = kit.get("/storage/objects", query=[("name", "p.png"), ("bucket", "photos")])
    assert read.body == png
    assert kit.get("/storage/stat", query=[("name", "p.png"), ("bucket", "photos")]).json()["contentType"] == "image/png"
    assert kit.delete("/storage/objects", query=[("name", "p.png"), ("bucket", "photos")]).status == 204
    assert kit.get("/storage/objects", query=[("name", "p.png"), ("bucket", "photos")]).status == 404


def test_the_photos_endpoint_signs_and_addresses() -> None:
    kit, _ = _photos()
    signed = kit.get("/storage/signed", query=[("name", "a.png"), ("access", "keep"), ("seconds", "60")]).text()
    assert signed.startswith("http://memory/default/a.png?access=") and "expires=" in signed
    assert "a.png" in kit.get("/storage/signed", query=[("name", "a.png")]).text()
    assert kit.get("/storage/url", query=[("name", "a.png"), ("bucket", "photos")]).text().endswith("/photos/a.png")


def test_a_photo_is_kept_in_the_bucket_and_named_to_the_listing() -> None:
    kit, sidecar = _photos()
    assert kit.post("/storage/listings/l-42/photos", RecordPhoto("42/a.png", "png", "image/png")).status == 204
    assert kit.get("/storage/objects", query=[("name", "42/a.png")]).text() == "png"
    (call,) = sidecar.calls
    assert (call.component_id, call.entity_id, call.name) == ("listing", "l-42", "record-photo")
    name = STRING.decode(call.payload.data)
    listing = EventSourcedTestKit.of(Listing, "l-42")
    listing.call("record-photo", name)
    assert listing.call("photos").reply == ListingState(["42/a.png"])
