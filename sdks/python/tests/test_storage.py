"""The storage client as a Python service sees it (feature 055): the rules every SDK shares, SigV4
against AWS's own vectors, the three stores held to one contract, who is given the service's buckets,
the choice of store from the environment, the directory store's URLs as the process serves them, and
the S3 store against a fake that checks every signature. The cases marked slow run the same client
through a real sidecar and against Garage."""

from __future__ import annotations

import asyncio
import hashlib
import json
import socket
import threading
import time
import urllib.error
import urllib.request
from collections.abc import AsyncIterator, Callable, Iterator
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, unquote, urlsplit

import pytest

from ankka import (
    Acl,
    Ankka,
    CommandContext,
    DirectoryBuckets,
    Endpoint,
    ErrorCode,
    EventSourcedEntity,
    InMemoryBuckets,
    ObjectRules,
    get,
    post,
)
from ankka import sigv4
from ankka._proto.ankka.protocol.v1 import discovery_pb2, endpoint_pb2
from ankka.agent import Agent
from ankka.autonomous import AutonomousAgent
from ankka.client import CommandError
from ankka.consumer import Consumer
from ankka.graph import GraphConsumer
from ankka.key_value_entity import KeyValueEntity
from ankka.storage import (
    DEFAULT_SEGMENT,
    STORAGE_BACKEND,
    STORAGE_ENDPOINT_ID,
    STORAGE_PATH,
    Buckets,
    S3Bucket,
    S3Buckets,
    S3Target,
    StorageSettingsError,
    UnavailableBuckets,
    from_environment,
    parse_listing,
)
from ankka.testkit import EndpointTestKit
from ankka.timed_action import TimedAction
from ankka.view import View
from tests.kinds import CheckoutWorkflow

SDK = Path(__file__).resolve().parent.parent
RULES = SDK / "proto" / "fixtures" / "storage" / "rules.json"
EXAMPLE = SDK / "examples" / "shopping_cart"
T0 = datetime(2026, 10, 19, 12, 0, 0, tzinfo=UTC)


def _fixture() -> dict[str, Any]:
    loaded: dict[str, Any] = json.loads(RULES.read_text("utf-8"))
    return loaded


def _rows(kind: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = _fixture()[kind]
    assert rows
    return rows


def _text(row: dict[str, Any]) -> str:
    return str(row["unit"]) * int(row["repeat"])


def _refusal(call: Callable[[], Any]) -> CommandError:
    """The refusal ``call`` raises, awaiting it when it is a coroutine."""
    with pytest.raises(CommandError) as refused:
        result = call()
        if asyncio.iscoroutine(result):
            asyncio.run(result)
    return refused.value


async def _names(bucket: Any, prefix: str = "") -> list[str]:
    return [entry.name async for entry in bucket.list(prefix)]


async def _chunks(*parts: bytes) -> AsyncIterator[bytes]:
    for part in parts:
        yield part


class _Clock:
    def __init__(self, at: datetime = T0) -> None:
        self.at = at

    def __call__(self) -> datetime:
        return self.at


# ── the rules ─────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("row", _rows("objectNames"), ids=lambda r: r["why"])
def test_every_object_name_in_the_fixture_gets_its_verdict_in_its_words(row: dict[str, Any]) -> None:
    problem = ObjectRules.name_problem(_text(row))
    assert (problem is None) == row["accepted"], problem
    if not row["accepted"]:
        assert problem is not None and row["says"] in problem
        # Every store refuses it before anything else, BAD_REQUEST, in the same words.
        refused = _refusal(lambda: InMemoryBuckets().default.stat(_text(row)))
        assert refused.error.code == ErrorCode.BAD_REQUEST
        assert row["says"] in refused.error.message


@pytest.mark.parametrize("row", _rows("prefixes"), ids=lambda r: r["why"])
def test_every_prefix_in_the_fixture_gets_its_verdict_in_its_words(row: dict[str, Any]) -> None:
    problem = ObjectRules.prefix_problem(_text(row))
    assert (problem is None) == row["accepted"], problem
    if row["accepted"]:
        assert asyncio.run(_names(InMemoryBuckets().default, _text(row))) == []
    else:
        assert problem is not None and row["says"] in problem
        # The listing is refused when it is asked for, not when it is first read.
        refused = _refusal(lambda: InMemoryBuckets().default.list(_text(row)))
        assert refused.error.code == ErrorCode.BAD_REQUEST
        assert row["says"] in refused.error.message


@pytest.mark.parametrize("row", _rows("bucketNames"), ids=lambda r: r["why"])
def test_every_bucket_name_in_the_fixture_gets_its_verdict(row: dict[str, Any]) -> None:
    problem = ObjectRules.bucket_name_problem(_text(row))
    assert (problem is None) == row["accepted"], problem
    if not row["accepted"]:
        assert problem is not None and row["says"] in problem
        with pytest.raises(ValueError, match="1 to 32 characters"):
            InMemoryBuckets({_text(row): "private"})


def test_a_signed_url_lasts_fifteen_minutes_unless_asked_and_seven_days_at_most() -> None:
    bound = _fixture()["signedUrl"]
    assert ObjectRules.DEFAULT_SIGNED_URL == timedelta(seconds=bound["defaultSeconds"])
    assert ObjectRules.MAX_SIGNED_URL == timedelta(seconds=bound["maxSeconds"])
    store = InMemoryBuckets(clock=_Clock())
    url = store.default.signed_url("a.jpg", "read")
    assert f"expires={int(T0.timestamp()) + bound['defaultSeconds']}" in url
    assert store.default.signed_url("a.jpg", "read", timedelta(seconds=bound["maxSeconds"]))
    refused = _refusal(lambda: store.default.signed_url("a.jpg", "read", timedelta(seconds=bound["maxSeconds"] + 1)))
    assert refused.error.code == ErrorCode.BAD_REQUEST and "at most 7 days" in refused.error.message
    assert "positive" in _refusal(lambda: store.default.signed_url("a.jpg", "read", timedelta(0))).error.message
    assert "read or keep" in _refusal(lambda: store.default.signed_url("a.jpg", "write")).error.message  # type: ignore[arg-type]


# ── SigV4 ─────────────────────────────────────────────────────────────────────


def test_sigv4_signs_aws_get_vanilla() -> None:
    request = sigv4.Request("GET", "https://example.amazonaws.com", "/")
    signed = sigv4.sign(
        request,
        {},
        sigv4.EMPTY_PAYLOAD,
        sigv4.Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
        "us-east-1",
        "service",
        datetime(2015, 8, 30, 12, 36, 0, tzinfo=UTC),
    )
    assert signed["x-amz-date"] == "20150830T123600Z"
    assert signed["Authorization"] == (
        "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
        "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
    )


S3_EXAMPLE = sigv4.Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
S3_EXAMPLE_TIME = datetime(2013, 5, 24, 0, 0, 0, tzinfo=UTC)


def test_sigv4_signs_aws_s3_get_object_example() -> None:
    request = sigv4.Request("GET", "https://examplebucket.s3.amazonaws.com", "/test.txt")
    headers = {"Range": "bytes=0-9", "x-amz-content-sha256": sigv4.EMPTY_PAYLOAD}
    signed = sigv4.sign(request, headers, sigv4.EMPTY_PAYLOAD, S3_EXAMPLE, "us-east-1", "s3", S3_EXAMPLE_TIME)
    assert signed["Authorization"] == (
        "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
        "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
        "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
    )


def test_sigv4_presigns_aws_s3_example() -> None:
    url = sigv4.presign(
        sigv4.Request("GET", "https://examplebucket.s3.amazonaws.com", "/test.txt"), S3_EXAMPLE, "us-east-1", "s3", 86400, S3_EXAMPLE_TIME
    )
    assert url.startswith("https://examplebucket.s3.amazonaws.com/test.txt?")
    query = dict(parse_qsl(urlsplit(url).query))
    assert query["X-Amz-Signature"] == "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"
    assert "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request" in url
    assert query["X-Amz-Expires"] == "86400" and query["X-Amz-SignedHeaders"] == "host"


def test_sigv4_encodes_paths_queries_and_hosts_as_s3_reads_them() -> None:
    request = sigv4.Request("GET", "http://localhost:3900", "/shop.listings/listings/42/front photo+1.jpg", (("prefix", "a/b c"),))
    assert request.canonical_path == "/shop.listings/listings/42/front%20photo%2B1.jpg"
    assert request.canonical_query == "prefix=a%2Fb%20c"
    assert request.host == "localhost:3900"
    assert sigv4.encode("café", encode_slash=True) == "caf%C3%A9"
    assert sigv4.encode("a/b", encode_slash=True) == "a%2Fb"


def test_credentials_never_show_their_secret() -> None:
    credentials = sigv4.Credentials("AKID", "the-secret-value")
    assert "the-secret-value" not in repr(credentials) and "the-secret-value" not in str(credentials)


# ── one contract, every store ─────────────────────────────────────────────────

DECLARED = {"photos": "public", "scans": "signed", "vault": "private"}


@pytest.fixture(params=["memory", "directory"])
def store(request: pytest.FixtureRequest, tmp_path: Path) -> Buckets:
    clock = _Clock()
    if request.param == "memory":
        return InMemoryBuckets(DECLARED, clock=clock)  # type: ignore[arg-type]
    return DirectoryBuckets(tmp_path / "storage", DECLARED, address_base="http://127.0.0.1:9000", clock=clock)  # type: ignore[arg-type]


def test_an_object_kept_is_read_back_with_its_content_type_length_and_time(store: Buckets) -> None:
    async def run() -> None:
        bucket = store.default
        await bucket.put("notes/a b+c.jpg", b"hello", content_type="image/jpeg")
        found = await bucket.get("notes/a b+c.jpg")
        assert found is not None
        assert (found.content_type, found.length, found.last_kept) == ("image/jpeg", 5, T0)
        assert await found.read() == b"hello"
        info = await bucket.stat("notes/a b+c.jpg")
        assert info is not None and (info.name, info.content_type, info.length) == ("notes/a b+c.jpg", "image/jpeg", 5)

    asyncio.run(run())


def test_a_stream_is_kept_with_its_declared_length_and_replaces_what_was_there(store: Buckets) -> None:
    async def run() -> None:
        bucket = store.default
        await bucket.put("doc", b"old", content_type="text/plain")
        await bucket.put("doc", _chunks(b"new ", b"bytes"), length=9, content_type="text/plain")
        found = await bucket.get("doc")
        assert found is not None and await found.read() == b"new bytes"

    asyncio.run(run())


def test_a_stream_that_ends_short_leaves_the_old_object(store: Buckets) -> None:
    async def run() -> None:
        bucket = store.default
        await bucket.put("doc", b"old", content_type="text/plain")
        with pytest.raises(CommandError) as refused:
            await bucket.put("doc", _chunks(b"only"), length=10, content_type="text/plain")
        assert refused.value.error.code == ErrorCode.BAD_REQUEST
        assert "after 4 of its declared 10 bytes" in refused.value.error.message
        found = await bucket.get("doc")
        assert found is not None and await found.read() == b"old"

    asyncio.run(run())


def test_a_stream_longer_than_declared_keeps_only_what_was_declared(store: Buckets) -> None:
    async def run() -> None:
        bucket = store.default
        await bucket.put("doc", _chunks(b"abc", b"def"), length=4, content_type="text/plain")
        info = await bucket.stat("doc")
        assert info is not None and info.length == 4
        found = await bucket.get("doc")
        assert found is not None and await found.read() == b"abcd"

    asyncio.run(run())


def test_a_stream_must_declare_its_length_and_no_length_is_negative(store: Buckets) -> None:
    async def run() -> None:
        bucket = store.default
        with pytest.raises(CommandError) as no_length:
            await bucket.put("doc", _chunks(b"x"), content_type="text/plain")
        assert no_length.value.error.code == ErrorCode.BAD_REQUEST and "declares its length" in no_length.value.error.message
        with pytest.raises(CommandError) as negative:
            await bucket.put("doc", b"x", length=-1, content_type="text/plain")
        assert "not negative" in negative.value.error.message
        assert await bucket.stat("doc") is None

    asyncio.run(run())


def test_nothing_kept_is_absent_and_a_second_delete_is_not_an_error(store: Buckets) -> None:
    async def run() -> None:
        bucket = store.default
        assert await bucket.get("never") is None
        assert await bucket.stat("never") is None
        await bucket.put("gone", b"x", content_type="text/plain")
        await bucket.delete("gone")
        await bucket.delete("gone")
        assert await bucket.get("gone") is None

    asyncio.run(run())


def test_a_listing_answers_the_names_under_a_prefix_in_order_and_each_bucket_its_own(store: Buckets) -> None:
    async def run() -> None:
        for name in ("listed/b", "listed/a", "elsewhere/c", "listed-not/d"):
            await store.default.put(name, b"xy", content_type="text/plain")
        await store.bucket("photos").put("listed/z", b"x", content_type="text/plain")
        assert await _names(store.default, "listed/") == ["listed/a", "listed/b"]
        assert await _names(store.default) == ["elsewhere/c", "listed-not/d", "listed/a", "listed/b"]
        entries = [e async for e in store.default.list("listed/a")]
        assert [(e.name, e.length, e.last_kept) for e in entries] == [("listed/a", 2, T0)]
        assert await _names(store.bucket("photos")) == ["listed/z"]
        assert await store.default.get("listed/z") is None

    asyncio.run(run())


def test_a_signed_url_comes_only_from_a_signed_bucket_and_an_address_only_from_a_public_one(store: Buckets) -> None:
    assert store.default.signed_url("a b.jpg", "read").startswith("http")
    assert store.bucket("scans").signed_url("a.jpg", "keep")
    photos = store.bucket("photos").url("listings/42/front.jpg")
    assert photos.endswith("listings/42/front.jpg") or photos.endswith("listings%2F42%2Ffront.jpg")
    for refused, words in (
        (_refusal(lambda: store.bucket("photos").signed_url("a.jpg", "read")), "bucket 'photos' is public"),
        (_refusal(lambda: store.bucket("vault").signed_url("a.jpg", "read")), "bucket 'vault' is private"),
        (_refusal(lambda: store.default.url("a.jpg")), "the service's unnamed bucket is signed"),
        (_refusal(lambda: store.bucket("vault").url("a.jpg")), "bucket 'vault' is private"),
    ):
        assert refused.error.code == ErrorCode.BAD_REQUEST, refused.error.message
        assert words in refused.error.message


def test_a_bucket_the_service_does_not_declare_is_unavailable_naming_those_it_does(store: Buckets) -> None:
    refused = _refusal(lambda: store.bucket("videos"))
    assert refused.error.code == ErrorCode.UNAVAILABLE
    assert "'videos'" in refused.error.message and "'photos', 'scans', 'vault'" in refused.error.message
    assert sorted(b.name or "" for b in store.buckets) == ["photos", "scans", "vault"]  # type: ignore[attr-defined]


def test_a_service_with_no_unnamed_bucket_is_refused_naming_provision_object_storage(tmp_path: Path) -> None:
    for none in (InMemoryBuckets(unnamed=False), DirectoryBuckets(tmp_path, unnamed=False), UnavailableBuckets()):
        refused = _refusal(lambda: none.default)
        assert refused.error.code == ErrorCode.UNAVAILABLE and "provisionObjectStorage" in refused.error.message


def test_the_memory_store_can_be_read_and_cleared_by_a_test() -> None:
    store = InMemoryBuckets()
    asyncio.run(store.default.put("a", b"x", content_type="text/plain"))
    assert store.objects[(None, "a")].data == b"x"
    store.declare("late", "private")
    assert store.bucket("late").name == "late"  # type: ignore[attr-defined]
    store.clear()
    assert store.objects == {}


# ── the directory store ───────────────────────────────────────────────────────


def test_the_directory_store_keeps_the_runtimes_layout_and_leaves_no_scratch(tmp_path: Path) -> None:
    store = DirectoryBuckets(tmp_path, {"photos": "public"}, clock=_Clock())
    asyncio.run(store.default.put("listings/42/front photo.jpg", b"hello", content_type="image/jpeg"))
    asyncio.run(store.bucket("photos").put("a", b"x", content_type="text/plain"))
    file = "listings%2F42%2Ffront%20photo.jpg"
    assert (tmp_path / DEFAULT_SEGMENT / "objects" / file).read_bytes() == b"hello"
    meta = json.loads((tmp_path / DEFAULT_SEGMENT / "meta" / f"{file}.json").read_text("utf-8"))
    assert meta == {"name": "listings/42/front photo.jpg", "contentType": "image/jpeg", "length": 5, "lastKept": meta["lastKept"]}
    assert meta["lastKept"].startswith("2026-10-19T12:00:00")
    assert (tmp_path / "photos" / "objects" / "a").read_bytes() == b"x"
    assert list((tmp_path / ".tmp").iterdir()) == []
    long_name = "n" * 300
    asyncio.run(store.default.put(long_name, b"long", content_type="text/plain"))
    hashed = "h-" + hashlib.sha256(long_name.encode()).hexdigest()
    assert (tmp_path / DEFAULT_SEGMENT / "objects" / hashed).read_bytes() == b"long"
    assert asyncio.run(_names(store.default, "n")) == [long_name]


def test_a_short_stream_leaves_no_scratch_file_behind(tmp_path: Path) -> None:
    store = DirectoryBuckets(tmp_path)
    with pytest.raises(CommandError):
        asyncio.run(store.default.put("doc", _chunks(b"ab"), length=5, content_type="text/plain"))
    assert list((tmp_path / ".tmp").iterdir()) == []
    assert not (tmp_path / DEFAULT_SEGMENT / "objects" / "doc").exists()


def test_the_signing_key_is_made_once_readable_by_its_owner_only(tmp_path: Path) -> None:
    store = DirectoryBuckets(tmp_path / "root")
    assert not (tmp_path / "root").exists()  # the root is made on first use
    url = store.default.signed_url("a", "read")
    key = tmp_path / "root" / ".key"
    assert len(key.read_bytes()) == 32
    assert key.stat().st_mode & 0o077 == 0
    assert DirectoryBuckets(tmp_path / "root").default.signed_url("a", "read").split("token=")[1] == url.split("token=")[1]


def _served(url: str) -> tuple[list[str], list[tuple[str, str]]]:
    """What the sidecar hands the process for a storage URL: the two path segments still
    percent-encoded, and the query decoded."""
    parts = urlsplit(url)
    assert parts.path.startswith(STORAGE_PATH + "/"), url
    segments = parts.path[len(STORAGE_PATH) + 1 :].split("/")
    return segments, parse_qsl(parts.query)


def test_a_directory_url_is_one_segment_per_bucket_and_name_on_the_sidecars_address(tmp_path: Path) -> None:
    store = DirectoryBuckets(tmp_path, {"photos": "public"}, address_base="http://127.0.0.1:9123/")
    assert store.bucket("photos").url("listings/42/front photo.jpg") == (
        "http://127.0.0.1:9123/_ankka/storage/photos/listings%2F42%2Ffront%20photo.jpg"
    )
    segments, query = _served(store.default.signed_url("a/b", "keep"))
    assert segments == [DEFAULT_SEGMENT, "a%2Fb"]
    assert [k for k, _ in query] == ["access", "expires", "token"]


def test_the_directory_endpoint_answers_only_what_a_url_was_signed_for(tmp_path: Path) -> None:
    clock = _Clock()
    store = DirectoryBuckets(tmp_path, {"photos": "public", "vault": "private"}, clock=clock)

    async def run() -> None:
        await store.default.put("listings/42/a b.jpg", b"jpeg bytes", content_type="image/jpeg")
        read = store.default.signed_url("listings/42/a b.jpg", "read")
        args, query = _served(read)
        assert await store.answer("GET", args, query, None, b"") == (200, "image/jpeg", b"jpeg bytes")
        # The same token for another access, another object, or tampered with, is refused.
        assert (await store.answer("PUT", args, query, "text/plain", b"x"))[0] == 403
        other, _ = _served(store.default.signed_url("listings/42/other.jpg", "read"))
        assert (await store.answer("GET", other, query, None, b""))[0] == 403
        tampered = [(k, v[:-1] + ("0" if v[-1] != "0" else "1") if k == "token" else v) for k, v in query]
        assert (await store.answer("GET", args, tampered, None, b""))[0] == 403
        later = [(k, str(int(v) + 1) if k == "expires" else v) for k, v in query]
        assert (await store.answer("GET", args, later, None, b""))[0] == 403
        assert (await store.answer("GET", args, [], None, b""))[0] == 403
        # A keep URL keeps the body under its content type.
        keep_args, keep_query = _served(store.default.signed_url("uploads/new.txt", "keep"))
        assert (await store.answer("PUT", keep_args, keep_query, "text/plain", b"uploaded"))[0] == 200
        found = await store.default.get("uploads/new.txt")
        assert found is not None and found.content_type == "text/plain" and await found.read() == b"uploaded"
        # Nothing there is 404, and a method no route serves 405.
        absent, absent_query = _served(store.default.signed_url("never", "read"))
        assert (await store.answer("GET", absent, absent_query, None, b""))[0] == 404
        assert (await store.answer("DELETE", args, query, None, b""))[0] == 405
        # A public bucket's address needs no signature; a private one's is refused.
        await store.bucket("photos").put("p.jpg", b"public", content_type="image/jpeg")
        public, _ = _served(store.bucket("photos").url("p.jpg"))
        assert await store.answer("GET", public, [], None, b"") == (200, "image/jpeg", b"public")
        await store.bucket("vault").put("v", b"secret", content_type="text/plain")
        assert (await store.answer("GET", ["vault", "v"], [], None, b""))[0] == 403
        assert (await store.answer("GET", ["videos", "v"], [], None, b""))[0] == 404
        # Past its expiry the URL reads nothing.
        clock.at = T0 + timedelta(minutes=16)
        assert (await store.answer("GET", args, query, None, b""))[0] == 403

    asyncio.run(run())


def test_a_restarted_directory_store_honours_the_urls_made_before(tmp_path: Path) -> None:
    first = DirectoryBuckets(tmp_path)
    asyncio.run(first.default.put("kept", b"before", content_type="text/plain"))
    args, query = _served(first.default.signed_url("kept", "read"))
    second = DirectoryBuckets(tmp_path)
    assert asyncio.run(second.answer("GET", args, query, None, b"")) == (200, "text/plain", b"before")


# ── who is given the service's buckets ────────────────────────────────────────


def test_an_endpoint_a_consumer_a_graph_consumer_a_timed_action_and_both_agents_are_given_them() -> None:
    for cls in (Endpoint, Consumer, GraphConsumer, TimedAction, Agent, AutonomousAgent):
        assert isinstance(getattr(cls, "storage", None), property), f"{cls.__name__} must have storage"


def test_an_entity_a_view_and_a_command_context_are_given_none() -> None:
    for cls in (EventSourcedEntity, KeyValueEntity, View, CommandContext):
        assert not hasattr(cls, "storage"), f"{cls.__name__} must have no storage"


def test_a_component_not_given_buckets_says_how_a_test_gives_them() -> None:
    class Bare(Endpoint):
        prefix = "/bare"
        acl = Acl.ALLOW_ALL

    with pytest.raises(RuntimeError, match="InMemoryBuckets"):
        Bare().storage


def test_a_workflow_keeps_an_object_in_a_step_and_not_in_a_command() -> None:
    workflow = CheckoutWorkflow()
    workflow.storage = InMemoryBuckets()
    refused = _refusal(lambda: workflow.storage)
    assert refused.error.code == ErrorCode.BAD_REQUEST and "in a step" in refused.error.message
    workflow._in_step = True
    assert isinstance(workflow.storage, InMemoryBuckets)


# ── choosing the store ────────────────────────────────────────────────────────

S3_ENV = {"ANKKA_S3_ENDPOINT": "http://garage:3900", "ANKKA_S3_REGION": "garage", "ANKKA_S3_BUCKET": "shop.cart"}


def test_the_backend_word_chooses_the_store() -> None:
    assert isinstance(from_environment({STORAGE_BACKEND: "memory"}, descriptor_directory=EXAMPLE), InMemoryBuckets)
    assert isinstance(from_environment({STORAGE_BACKEND: "directory"}, descriptor_directory=EXAMPLE), DirectoryBuckets)
    assert isinstance(from_environment({STORAGE_BACKEND: "s3", **S3_ENV}), S3Buckets)
    with pytest.raises(StorageSettingsError, match="s3 store needs an endpoint"):
        from_environment({STORAGE_BACKEND: "s3"})
    with pytest.raises(StorageSettingsError, match="one of s3, directory or memory"):
        from_environment({STORAGE_BACKEND: "disk"})


def test_with_no_word_s3_variables_choose_s3_a_laptop_the_directory_and_a_pod_nothing() -> None:
    assert isinstance(from_environment(S3_ENV), S3Buckets)
    assert isinstance(from_environment({}, descriptor_directory=EXAMPLE), DirectoryBuckets)
    pod = from_environment({"KUBERNETES_SERVICE_HOST": "10.43.0.1"})
    assert isinstance(pod, UnavailableBuckets)
    assert "provisionObjectStorage" in _refusal(lambda: pod.default).error.message
    assert isinstance(from_environment({"ANKKA_CLUSTER_MODE": "kubernetes"}), UnavailableBuckets)


def test_the_memory_and_directory_stores_hold_what_service_json_declares(tmp_path: Path) -> None:
    store = from_environment({STORAGE_BACKEND: "memory"}, descriptor_directory=EXAMPLE)
    assert store.bucket("photos").url("a.jpg").endswith("/a.jpg")
    assert "'photos'" in _refusal(lambda: store.bucket("videos")).error.message
    (tmp_path / "service.json").write_text(json.dumps({"name": "x", "service": {"buckets": [{"name": "scans", "exposure": "signed"}]}}))
    named_only = from_environment({STORAGE_BACKEND: "directory", "ANKKA_STORAGE_DIR": str(tmp_path / "s")}, descriptor_directory=tmp_path)
    assert isinstance(named_only, DirectoryBuckets) and named_only.root == (tmp_path / "s")
    assert "provisionObjectStorage" in _refusal(lambda: named_only.default).error.message
    assert named_only.bucket("scans").signed_url("a", "read")


def test_the_s3_variables_are_read_as_the_runtime_reads_them() -> None:
    env = {
        **S3_ENV,
        "ANKKA_S3_ACCESS_KEY": "AK",
        "ANKKA_S3_SECRET_KEY": "SK",
        "ANKKA_S3_PUBLIC_ENDPOINT": "https://storage.example.test",
        "ANKKA_S3_BUCKET_PHOTOS": "shop.cart.photos",
        "ANKKA_S3_EXPOSURE_PHOTOS": "public",
        "ANKKA_S3_PUBLIC_URL_PHOTOS": "https://photos.example.test/",
        "ANKKA_S3_BUCKET_OLD_PHOTOS": "shop.cart.old-photos",
        "ANKKA_S3_ENDPOINT_ARCHIVE": "https://s3.eu-west-2.amazonaws.com",
        "ANKKA_S3_REGION_ARCHIVE": "eu-west-2",
        "ANKKA_S3_BUCKET_ARCHIVE": "acme-archive",
        "ANKKA_S3_ACCESS_KEY_ARCHIVE": "AK2",
        "ANKKA_S3_SECRET_KEY_ARCHIVE": "SK2",
    }
    store = from_environment(env)
    assert sorted(b.name or "" for b in store.buckets) == ["archive", "old-photos", "photos"]  # type: ignore[attr-defined]
    assert store.bucket("photos").url("a b.jpg") == "https://photos.example.test/a%20b.jpg"
    assert "is private" in _refusal(lambda: store.bucket("old-photos").url("a")).error.message
    assert store.default.signed_url("a", "read").startswith("https://storage.example.test/shop.cart/a?")
    assert store.bucket("archive").signed_url("a", "keep").startswith("https://s3.eu-west-2.amazonaws.com/acme-archive/a?")
    for broken, words in (
        ({**env, "ANKKA_S3_EXPOSURE_PHOTOS": "open"}, "one of private, signed or public"),
        ({**env, "ANKKA_S3_PUBLIC_URL_PHOTOS": ""}, "ANKKA_S3_PUBLIC_URL_PHOTOS is not set"),
        ({k: v for k, v in env.items() if k != "ANKKA_S3_REGION_ARCHIVE"}, "ANKKA_S3_REGION_ARCHIVE is not set"),
        ({"ANKKA_S3_BUCKET_PHOTOS": "p"}, "ANKKA_S3_ENDPOINT is not set"),
        ({**env, "ANKKA_S3_ENDPOINT": "garage:3900"}, "not a URL"),
    ):
        with pytest.raises(StorageSettingsError, match=words):
            from_environment(broken)
        try:
            from_environment(broken)
        except StorageSettingsError as e:
            assert "SK" not in str(e).replace("SK2", "").replace("TASK", ""), "a setting's refusal names no secret"


def test_an_s3_bucket_with_no_credential_refuses_to_sign() -> None:
    store = from_environment(S3_ENV)
    refused = _refusal(lambda: store.default.put("a", b"x", content_type="text/plain"))
    assert refused.error.code == ErrorCode.UNAVAILABLE and "no storage credential" in refused.error.message


# ── the process's server ──────────────────────────────────────────────────────


def _discover(storage: Buckets, protocol: str) -> discovery_pb2.Spec:
    from ankka.server import DiscoveryServicer

    servicer = DiscoveryServicer(Ankka.service().register(KeeperEndpoint)._registry, storage)
    spec: discovery_pb2.Spec = asyncio.run(servicer.Discover(discovery_pb2.SidecarInfo(protocol_version=protocol), None))
    return spec


def test_discovery_declares_the_storage_endpoint_only_for_a_directory_store_and_a_121_sidecar(tmp_path: Path) -> None:
    declared = [e for e in _discover(DirectoryBuckets(tmp_path), "1.21").endpoints if e.id == STORAGE_ENDPOINT_ID]
    assert len(declared) == 1
    assert declared[0].prefix == STORAGE_PATH and declared[0].acl == discovery_pb2.Endpoint.ALLOW_ALL
    assert sorted((r.id, r.method, r.template) for r in declared[0].routes) == [
        ("keep", "PUT", "/{bucket}/{name}"),
        ("read", "GET", "/{bucket}/{name}"),
    ]
    assert not [e for e in _discover(DirectoryBuckets(tmp_path), "1.20").endpoints if e.id == STORAGE_ENDPOINT_ID]
    assert not [e for e in _discover(InMemoryBuckets(), "1.21").endpoints if e.id == STORAGE_ENDPOINT_ID]


def test_the_server_answers_the_storage_endpoint_from_the_directory_store(tmp_path: Path) -> None:
    from ankka.server import HttpServicer

    store = DirectoryBuckets(tmp_path, address_base="http://127.0.0.1:9000")
    asyncio.run(store.default.put("a/b c.txt", b"served", content_type="text/plain"))
    args, query = _served(store.default.signed_url("a/b c.txt", "read"))
    servicer = HttpServicer(Ankka.service().register(KeeperEndpoint)._registry, None, store)  # type: ignore[arg-type]
    Pair = endpoint_pb2.HttpRequest.Pair

    def handle(route: str, q: list[tuple[str, str]], body: bytes = b"", content_type: str = "") -> endpoint_pb2.HttpResponse:
        request = endpoint_pb2.HttpRequest(
            endpoint_id=STORAGE_ENDPOINT_ID,
            route_id=route,
            path_args=args,
            query=[Pair(name=k, value=v) for k, v in q],
            content_type=content_type,
            body=body,
        )
        reply: endpoint_pb2.HttpReply = asyncio.run(servicer.Handle(request, None))
        return reply.response

    read = handle("read", query)
    assert (read.status, read.content_type, read.body) == (200, "text/plain", b"served")
    assert handle("read", []).status == 403
    keep_args, keep_query = _served(store.default.signed_url("a/b c.txt", "keep"))
    assert keep_args == args
    assert handle("keep", keep_query, b"<p>replaced</p>", "text/html").status == 200
    found = asyncio.run(store.default.get("a/b c.txt"))
    assert found is not None and found.content_type == "text/html" and asyncio.run(found.read()) == b"<p>replaced</p>"


# ── the S3 store, against a fake that checks every signature ─────────────────


class FakeS3:
    """An S3 store on loopback that keeps objects in memory and believes only a request signed with
    its one key pair: every header signature and every presigned URL is computed again with
    ``sigv4`` from what arrived on the wire, and anything else is ``403 SignatureDoesNotMatch``."""

    ACCESS, SECRET, REGION = "AKFAKE", "fake-secret-not-logged", "test"

    def __init__(self, page_size: int = 2) -> None:
        self.objects: dict[tuple[str, str], tuple[str, bytes]] = {}
        self.seen: list[tuple[str, str, dict[str, str]]] = []
        self.page_size = page_size
        fake = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_: Any) -> None:
                pass

            def _answer(self, status: int, body: bytes = b"", headers: dict[str, str] | None = None) -> None:
                self.send_response(status)
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                if "Content-Length" not in (headers or {}):
                    self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)

            def _error(self, status: int, code: str) -> None:
                self._answer(status, f"<Error><Code>{code}</Code></Error>".encode(), {"Content-Type": "application/xml"})

            def _handle(self) -> None:
                parts = urlsplit(self.path)
                path, query = unquote(parts.path), tuple(parse_qsl(parts.query, keep_blank_values=True))
                headers = {k.lower(): v for k, v in self.headers.items()}
                fake.seen.append((self.command, self.path, headers))
                body = b""
                if self.command == "PUT":
                    if "transfer-encoding" in headers or "content-length" not in headers:
                        return self._error(411, "MissingContentLength")
                    declared = int(headers["content-length"])
                    body = self.rfile.read(declared)
                    if len(body) < declared:
                        self.close_connection = True
                        return  # the client gave up: nothing is kept
                if not fake.believes(self.command, path, query, headers):
                    return self._error(403, "SignatureDoesNotMatch")
                segments = path.lstrip("/").split("/", 1)
                bucket, key = segments[0], segments[1] if len(segments) > 1 else ""
                if self.command == "PUT":
                    fake.objects[(bucket, key)] = (headers.get("content-type", "application/octet-stream"), body)
                    return self._answer(200)
                if self.command == "DELETE":
                    fake.objects.pop((bucket, key), None)
                    return self._answer(204)
                if key == "" and dict(query).get("list-type") == "2":
                    return self._answer(200, fake.listing(bucket, dict(query)), {"Content-Type": "application/xml"})
                found = fake.objects.get((bucket, key))
                if found is None:
                    return self._error(404, "NoSuchKey")
                return self._answer(
                    200,
                    found[1],
                    {"Content-Type": found[0], "Content-Length": str(len(found[1])), "Last-Modified": "Mon, 19 Oct 2026 12:00:00 GMT"},
                )

            do_GET = do_PUT = do_HEAD = do_DELETE = _handle

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.endpoint = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def believes(self, method: str, path: str, query: tuple[tuple[str, str], ...], headers: dict[str, str]) -> bool:
        credentials = sigv4.Credentials(self.ACCESS, self.SECRET)
        params = dict(query)
        if "X-Amz-Signature" in params:
            now = datetime.strptime(params["X-Amz-Date"], "%Y%m%dT%H%M%SZ").replace(tzinfo=UTC)
            if now + timedelta(seconds=int(params["X-Amz-Expires"])) < datetime.now(UTC):
                return False
            unsigned = tuple((k, v) for k, v in query if not k.startswith("X-Amz-"))
            again = sigv4.presign(
                sigv4.Request(method, self.endpoint, path, unsigned), credentials, self.REGION, "s3", int(params["X-Amz-Expires"]), now
            )
            return dict(parse_qsl(urlsplit(again).query))["X-Amz-Signature"] == params["X-Amz-Signature"]
        authorization = headers.get("authorization", "")
        names = authorization.split("SignedHeaders=")[1].split(",")[0].split(";") if "SignedHeaders=" in authorization else []
        signed = {n: headers[n] for n in names if n not in ("host", "x-amz-date") and n in headers}
        now = datetime.strptime(headers.get("x-amz-date", "19700101T000000Z"), "%Y%m%dT%H%M%SZ").replace(tzinfo=UTC)
        again = sigv4.sign(
            sigv4.Request(method, self.endpoint, path, query), signed, headers.get("x-amz-content-sha256", ""), credentials, self.REGION, "s3", now
        )
        return bool(authorization) and again["Authorization"] == authorization and headers.get("host") == urlsplit(self.endpoint).netloc

    def listing(self, bucket: str, query: dict[str, str]) -> bytes:
        names = sorted(k for b, k in self.objects if b == bucket and k.startswith(query.get("prefix", "")))
        start = int(query.get("continuation-token") or 0)
        page = names[start : start + self.page_size]
        more = start + self.page_size < len(names)
        contents = "".join(
            f"<Contents><Key>{k}</Key><Size>{len(self.objects[(bucket, k)][1])}</Size>"
            f"<LastModified>2026-10-19T12:00:00.000Z</LastModified></Contents>"
            for k in page
        )
        token = f"<NextContinuationToken>{start + self.page_size}</NextContinuationToken>" if more else ""
        return (
            '<?xml version="1.0" encoding="UTF-8"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
            f"<IsTruncated>{'true' if more else 'false'}</IsTruncated>{contents}{token}</ListBucketResult>"
        ).encode()

    def bucket(self, exposure: str = "private", secret: str | None = None, name: str | None = None) -> S3Bucket:
        target = S3Target(self.endpoint, self.REGION, "shop.cart", sigv4.Credentials(self.ACCESS, secret or self.SECRET), self.endpoint)
        return S3Bucket(target, name, exposure, timeout=5)  # type: ignore[arg-type]

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def fake_s3() -> Iterator[FakeS3]:
    fake = FakeS3()
    yield fake
    fake.close()


def test_an_s3_put_is_one_signed_request_with_its_length_and_an_unsigned_payload(fake_s3: FakeS3) -> None:
    bucket = fake_s3.bucket()

    async def run() -> None:
        await bucket.put("listings/42/front photo+1.jpg", _chunks(b"hel", b"lo"), length=5, content_type="image/jpeg")
        found = await bucket.get("listings/42/front photo+1.jpg")
        assert found is not None and found.content_type == "image/jpeg" and found.length == 5
        assert await found.read() == b"hello"
        info = await bucket.stat("listings/42/front photo+1.jpg")
        assert info is not None and info.length == 5 and info.last_kept == datetime(2026, 10, 19, 12, tzinfo=UTC)

    asyncio.run(run())
    method, path, headers = fake_s3.seen[0]
    assert (method, path) == ("PUT", "/shop.cart/listings/42/front%20photo%2B1.jpg")
    assert headers["content-length"] == "5" and "transfer-encoding" not in headers
    assert headers["x-amz-content-sha256"] == "UNSIGNED-PAYLOAD"
    assert fake_s3.objects[("shop.cart", "listings/42/front photo+1.jpg")] == ("image/jpeg", b"hello")


def test_an_s3_put_whose_stream_ends_short_keeps_nothing(fake_s3: FakeS3) -> None:
    bucket = fake_s3.bucket()
    asyncio.run(bucket.put("doc", b"old", content_type="text/plain"))
    refused = _refusal(lambda: bucket.put("doc", _chunks(b"ne"), length=10, content_type="text/plain"))
    assert refused.error.code == ErrorCode.BAD_REQUEST and "after 2 of its declared 10 bytes" in refused.error.message
    time.sleep(0.2)
    assert fake_s3.objects[("shop.cart", "doc")] == ("text/plain", b"old")


def test_an_s3_listing_follows_every_page_and_absent_is_none(fake_s3: FakeS3) -> None:
    bucket = fake_s3.bucket()

    async def run() -> None:
        for name in ("p/e", "p/a", "p/c", "p/b", "p/d", "q/x"):
            await bucket.put(name, b"x", content_type="text/plain")
        assert await _names(bucket, "p/") == ["p/a", "p/b", "p/c", "p/d", "p/e"]
        assert await bucket.get("never") is None and await bucket.stat("never") is None
        await bucket.delete("p/a")
        await bucket.delete("p/a")
        assert await _names(bucket, "p/a") == []

    asyncio.run(run())
    pages = [p for m, p, _ in fake_s3.seen if m == "GET" and "list-type=2" in p and p.endswith("prefix=p%2F")]
    assert len(pages) == 3, pages


def test_an_s3_presigned_url_is_believed_by_the_store_for_its_method(fake_s3: FakeS3) -> None:
    bucket = fake_s3.bucket("supplied", name="archive")
    asyncio.run(bucket.put("a b/c.txt", b"signed", content_type="text/plain"))
    with urllib.request.urlopen(bucket.signed_url("a b/c.txt", "read"), timeout=5) as response:
        assert response.read() == b"signed"
    keep = urllib.request.Request(bucket.signed_url("up.txt", "keep"), data=b"put by url", method="PUT")
    with urllib.request.urlopen(keep, timeout=5) as response:
        assert response.status == 200
    assert fake_s3.objects[("shop.cart", "up.txt")][1] == b"put by url"
    with pytest.raises(urllib.error.HTTPError) as wrong_method:
        urllib.request.urlopen(urllib.request.Request(bucket.signed_url("a b/c.txt", "read"), data=b"x", method="PUT"), timeout=5)
    assert wrong_method.value.code == 403


def test_an_s3_store_that_refuses_or_cannot_be_reached_is_unavailable_naming_it_and_no_secret(fake_s3: FakeS3) -> None:
    refused = _refusal(lambda: fake_s3.bucket(secret="wrong-secret").get("a"))
    assert refused.error.code == ErrorCode.UNAVAILABLE
    assert "refused the request (403): SignatureDoesNotMatch" in refused.error.message
    assert "wrong-secret" not in refused.error.message and FakeS3.SECRET not in refused.error.message
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        closed = s.getsockname()[1]
    target = S3Target(f"http://127.0.0.1:{closed}", "test", "b", sigv4.Credentials("AK", "SK"))
    unreachable = _refusal(lambda: S3Bucket(target, None, "private", timeout=2).stat("a"))
    assert unreachable.error.code == ErrorCode.UNAVAILABLE and "cannot be reached" in unreachable.error.message


def test_a_listing_is_read_from_list_objects_v2() -> None:
    entries, token = parse_listing(
        b'<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>true</IsTruncated>'
        b"<Contents><Key>a &amp; b</Key><Size>3</Size><LastModified>2026-10-19T12:00:00.000Z</LastModified></Contents>"
        b"<NextContinuationToken>t1</NextContinuationToken></ListBucketResult>"
    )
    assert [(e.name, e.length, e.last_kept) for e in entries] == [("a & b", 3, datetime(2026, 10, 19, 12, tzinfo=UTC))]
    assert token == "t1"
    assert parse_listing(b"<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>") == ([], None)


# ── the test kits and the conformance reference ──────────────────────────────


class KeeperEndpoint(Endpoint):
    prefix = "/kept"
    acl = Acl.ALLOW_ALL

    @post("/{name}")
    async def keep(self, name: str, body: str) -> str:
        await self.storage.default.put(name, body.encode("utf-8"), content_type="text/plain")
        return "kept"

    @get("/signed")
    def signed(self) -> str:
        return self.storage.default.signed_url(self.request.query_param("name") or "", "read")


def test_the_endpoint_unit_kit_hands_over_buckets_a_test_reads_back() -> None:
    kit = EndpointTestKit.of(KeeperEndpoint)
    assert kit.request("POST", "/kept/note", body="from the handler").text() == "kept"
    found = asyncio.run(kit.storage.default.get("note"))
    assert found is not None and asyncio.run(found.read()) == b"from the handler"
    mine = InMemoryBuckets()
    assert EndpointTestKit.of(KeeperEndpoint, storage=mine).storage is mine


def test_every_unit_kit_gives_its_component_the_buckets() -> None:
    from ankka.testkit import unit

    given = {
        "EndpointTestKit",
        "WorkflowTestKit",
        "ConsumerTestKit",
        "GraphConsumerTestKit",
        "AgentTestKit",
        "TimedActionTestKit",
        "AutonomousAgentTestKit",
    }
    kits = {n for n in dir(unit) if n.endswith("TestKit") and isinstance(getattr(unit, n), type)}
    assert given < kits
    for name in kits:
        holds = "storage" in getattr(unit, name).__init__.__code__.co_varnames
        assert holds == (name in given), f"{name} {'holds' if holds else 'lacks'} storage"


def _conformance() -> EndpointTestKit:
    from examples.shopping_cart.conformance import ConformanceEndpoint

    storage = from_environment({STORAGE_BACKEND: "memory"}, descriptor_directory=EXAMPLE)
    return EndpointTestKit(ConformanceEndpoint(None), storage)  # type: ignore[arg-type]


def _ask(kit: EndpointTestKit, method: str, body: str | None = None, ctype: str = "text/plain", **params: str) -> dict[str, Any]:
    response = kit.request(method, "/conformance/storage", body=body, query=list(params.items()), headers=[("Content-Type", ctype)])
    assert response.status == 200, response.text()
    answer: dict[str, Any] = response.json()
    return answer


def test_the_conformance_reference_answers_the_storage_cases_as_the_suite_asks_them() -> None:
    """The cases of ``ConformanceSuite``'s ``storage.*``, through the reference's routes and the store
    the launcher gives it, so a Python break shows here before the platform's suite runs."""
    kit = _conformance()
    assert _ask(kit, "POST", "hello", "image/jpeg", name="notes/a.jpg")["outcome"] == "ok"
    got = _ask(kit, "GET", op="get", name="notes/a.jpg")
    assert (got["outcome"], got["contentType"], got["text"], got["length"]) == ("ok", "image/jpeg", "hello", 5)
    for n in ("listed/b", "listed/a", "elsewhere/c"):
        _ask(kit, "POST", "x", "image/jpeg", name=n)
    assert _ask(kit, "GET", op="list", prefix="listed/")["names"] == ["listed/a", "listed/b"]
    assert _ask(kit, "GET", op="stat", name="stat/never")["outcome"] == "absent"
    assert [_ask(kit, "DELETE", name="notes/a.jpg")["outcome"] for _ in range(2)] == ["ok", "ok"]
    for field, rule in (("objectNames", "name"), ("prefixes", "prefix")):
        for row in _rows(field):
            answer = _ask(kit, "POST", _text(row), rules=rule)
            if row["accepted"]:
                assert answer["outcome"] == "ok", (row["why"], answer)
            else:
                assert (answer["outcome"], answer["code"]) == ("refused", "BAD_REQUEST"), (row["why"], answer)
                assert row["says"] in answer["message"], (row["why"], answer)
    unknown = _ask(kit, "GET", op="get", bucket="videos", name="x")
    assert (unknown["outcome"], unknown["code"]) == ("refused", "UNAVAILABLE")
    assert "'videos'" in unknown["message"] and "'photos'" in unknown["message"]
    url = _ask(kit, "GET", op="url", bucket="photos", name="listings/42/front.jpg")
    assert url["outcome"] == "ok" and url["url"].endswith("/listings/42/front.jpg")
    assert _ask(kit, "GET", op="url", name="front.jpg")["code"] == "BAD_REQUEST"
    _ask(kit, "POST", "named", "image/jpeg", bucket="photos", name="front.jpg")
    assert _ask(kit, "GET", op="get", bucket="photos", name="front.jpg")["text"] == "named"
    assert _ask(kit, "GET", op="get", name="front.jpg")["outcome"] == "absent"
    large = _ask(kit, "POST", "x" * 5_000_000, "application/octet-stream", name="large/five-megabytes")
    assert large["outcome"] == "ok"
    assert _ask(kit, "GET", op="stat", name="large/five-megabytes")["length"] == 5_000_000


def test_the_conformance_launcher_runs_the_reference_on_the_memory_store() -> None:
    source = (SDK / "src" / "ankka" / "_conformance.py").read_text("utf-8")
    assert 'STORAGE_BACKEND: os.environ.get(STORAGE_BACKEND) or "memory"' in source
    assert 'descriptor_directory=SDK / "examples" / "shopping_cart"' in source
    descriptor = json.loads((EXAMPLE / "service.json").read_text("utf-8"))["service"]
    assert descriptor["provisionObjectStorage"] is True
    assert descriptor["buckets"] == [{"name": "photos", "exposure": "public"}]


# ── through a real sidecar, and against Garage ───────────────────────────────


@pytest.mark.slow
async def test_a_handler_behind_the_sidecar_keeps_an_object_the_test_reads_from_the_kit() -> None:
    from ankka.testkit.integration import AnkkaTestKit

    service = Ankka.service().register(KeeperEndpoint)
    async with await AnkkaTestKit.start(service) as kit:
        assert isinstance(kit.storage, InMemoryBuckets)
        answer = await kit.http.post("/kept/through-the-sidecar", content=b"kept by a handler", headers={"Content-Type": "text/plain"})
        assert answer.status_code == 200, answer.text
        found = await kit.storage.default.get("through-the-sidecar")
        assert found is not None and await found.read() == b"kept by a handler"
        # An object above 4 MiB, the gRPC default, reaches the process: no object passes through
        # the sidecar's storage import, but a route's body does pass through its call.
        big = await kit.http.post("/kept/large", content=b"x" * 5_000_000, headers={"Content-Type": "text/plain"})
        assert big.status_code == 200, big.text[:300]
        info = await kit.storage.default.stat("large")
        assert info is not None and info.length == 5_000_000


@pytest.mark.slow
async def test_the_sidecar_serves_the_directory_stores_urls_from_the_process(tmp_path: Path) -> None:
    """A sidecar beside a process keeps no store of its own, so it serves the process's
    ``/_ankka/storage`` with nothing set: no object store's variables, no directory."""
    from ankka.testkit.integration import AnkkaTestKit

    base: list[str] = ["http://unset"]
    store = DirectoryBuckets(tmp_path, {"photos": "public"}, address_base=lambda: base[0])
    service = Ankka.service().register(KeeperEndpoint)
    async with await AnkkaTestKit.start(service, storage=store) as kit:
        base[0] = str(kit.http.base_url).rstrip("/")
        await store.default.put("listings/42/a b.txt", b"from the directory", content_type="text/plain")
        signed = (await kit.http.get("/kept/signed", params={"name": "listings/42/a b.txt"})).text
        assert signed.startswith(base[0] + "/_ankka/storage/_default/listings%2F42%2Fa%20b.txt?"), signed
        read = await kit.http.get(signed)
        assert (read.status_code, read.headers["content-type"].split(";")[0], read.content) == (200, "text/plain", b"from the directory")
        assert (await kit.http.get(signed.replace("access=read", "access=keep"))).status_code == 403
        assert (await kit.http.get(signed.split("?")[0])).status_code == 403
        keep = store.default.signed_url("uploads/u.txt", "keep")
        kept = await kit.http.put(keep, content=b"put through the sidecar", headers={"Content-Type": "text/markdown"})
        assert kept.status_code == 200, kept.text
        found = await store.default.get("uploads/u.txt")
        assert found is not None and found.content_type == "text/markdown" and await found.read() == b"put through the sidecar"
        await store.bucket("photos").put("p.jpg", b"public", content_type="image/jpeg")
        public = await kit.http.get(store.bucket("photos").url("p.jpg"))
        assert (public.status_code, public.content) == (200, b"public")


GARAGE_IMAGE = "dxflrs/garage:v2.3.0"
GARAGE_CONFIG = """metadata_dir = "/var/lib/garage/meta"
data_dir = "/var/lib/garage/data"
db_engine = "lmdb"
replication_factor = 1
rpc_bind_addr = "[::]:3901"

[s3_api]
s3_region = "garage"
api_bind_addr = "[::]:3900"

[admin]
api_bind_addr = "[::]:3903"
"""


@pytest.fixture(scope="module")
def garage(tmp_path_factory: pytest.TempPathFactory) -> Iterator[tuple[str, sigv4.Credentials]]:
    from testcontainers.core.container import DockerContainer

    config = tmp_path_factory.mktemp("garage") / "garage.toml"
    config.write_text(GARAGE_CONFIG)
    config.chmod(0o644)
    token = "admin-token-for-this-test-only"
    container = (
        DockerContainer(GARAGE_IMAGE)
        .with_volume_mapping(str(config), "/etc/garage.toml", "ro")
        .with_env("GARAGE_RPC_SECRET", "0" * 64)
        .with_env("GARAGE_ADMIN_TOKEN", token)
        .with_command("/garage server --single-node")
        .with_exposed_ports(3900, 3903)
    )
    container.start()
    try:
        admin = f"http://127.0.0.1:{container.get_exposed_port(3903)}"

        def call(path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
            request = urllib.request.Request(
                admin + path,
                data=None if body is None else json.dumps(body).encode(),
                method="GET" if body is None else "POST",
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            )
            with urllib.request.urlopen(request, timeout=10) as response:
                answer: dict[str, Any] = json.loads(response.read() or b"{}")
                return answer

        deadline = time.monotonic() + 60
        while True:
            try:
                with urllib.request.urlopen(admin + "/health", timeout=2) as response:
                    if response.status == 200:
                        break
            except OSError:
                pass
            if time.monotonic() > deadline:
                raise AssertionError("garage was not healthy within 60s:\n" + container.get_logs()[0].decode()[-2000:])
            time.sleep(0.5)
        bucket = call("/v2/CreateBucket", {"globalAlias": "py-storage"})
        key = call("/v2/CreateKey", {"name": "py-storage"})
        call(
            "/v2/AllowBucketKey",
            {"bucketId": bucket["id"], "accessKeyId": key["accessKeyId"], "permissions": {"read": True, "write": True, "owner": True}},
        )
        yield f"http://127.0.0.1:{container.get_exposed_port(3900)}", sigv4.Credentials(key["accessKeyId"], key["secretAccessKey"])
    finally:
        container.stop()


def _garage_bucket(garage: tuple[str, sigv4.Credentials], exposure: str = "private", name: str | None = None) -> S3Bucket:
    endpoint, credentials = garage
    return S3Bucket(S3Target(endpoint, "garage", "py-storage", credentials, endpoint), name, exposure, timeout=10)  # type: ignore[arg-type]


@pytest.mark.slow
async def test_garage_believes_every_request_the_s3_store_signs(garage: tuple[str, sigv4.Credentials]) -> None:
    bucket = _garage_bucket(garage)
    awkward = "listings/42/café front photo+1 & (2).jpg"
    await bucket.put(awkward, _chunks(b"jpeg ", b"bytes"), length=10, content_type="image/jpeg")
    found = await bucket.get(awkward)
    assert found is not None and (found.content_type, found.length) == ("image/jpeg", 10)
    assert await found.read() == b"jpeg bytes"
    info = await bucket.stat(awkward)
    assert info is not None and info.length == 10 and info.content_type == "image/jpeg"
    for name in ("p/b", "p/a", "p/c d"):
        await bucket.put(name, b"x", content_type="text/plain")
    assert await _names(bucket, "p/") == ["p/a", "p/b", "p/c d"]
    assert await bucket.get("never") is None and await bucket.stat("never") is None
    await bucket.delete("p/a")
    await bucket.delete("p/a")
    assert await _names(bucket, "p/") == ["p/b", "p/c d"]
    empty = "empty/object"
    await bucket.put(empty, b"", content_type="text/plain")
    kept_empty = await bucket.get(empty)
    assert kept_empty is not None and await kept_empty.read() == b""


@pytest.mark.slow
async def test_garage_keeps_nothing_from_a_stream_that_ends_short(garage: tuple[str, sigv4.Credentials]) -> None:
    bucket = _garage_bucket(garage)
    await bucket.put("short/doc", b"old", content_type="text/plain")
    with pytest.raises(CommandError) as refused:
        await bucket.put("short/doc", _chunks(b"x" * 1000), length=100_000, content_type="text/plain")
    assert "after 1000 of its declared 100000 bytes" in refused.value.error.message
    found = await bucket.get("short/doc")
    assert found is not None and await found.read() == b"old"


@pytest.mark.slow
async def test_garage_honours_the_s3_stores_signed_urls(garage: tuple[str, sigv4.Credentials]) -> None:
    import httpx

    bucket = _garage_bucket(garage, "signed")
    await bucket.put("signed/a b+c.txt", b"read by url", content_type="text/plain")
    async with httpx.AsyncClient(timeout=10) as http:
        read = await http.get(bucket.signed_url("signed/a b+c.txt", "read"))
        assert (read.status_code, read.content) == (200, b"read by url")
        kept = await http.put(bucket.signed_url("signed/up.txt", "keep"), content=b"kept by url")
        assert kept.status_code == 200, kept.text
        assert (await http.put(bucket.signed_url("signed/up.txt", "read"), content=b"no")).status_code == 403
    found = await bucket.get("signed/up.txt")
    assert found is not None and await found.read() == b"kept by url"
    wrong = S3Bucket(S3Target(garage[0], "garage", "py-storage", sigv4.Credentials(garage[1].access_key, "not-the-secret")), None, "private")
    with pytest.raises(CommandError) as refused:
        await wrong.stat("signed/up.txt")
    assert refused.value.error.code == ErrorCode.UNAVAILABLE and "not-the-secret" not in refused.value.error.message
