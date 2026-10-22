"""AWS Signature Version 4, as S3 and every store that speaks it reads it: a request signed in its
headers, and a URL signed in its query string (feature 055).

The signer works on a request's parts — its method, its endpoint, its path and query *before*
encoding, the headers it signs and the hash of its payload — so the header form and the query form
share one canonical request, and the documentation's vectors test it with no client at all. The same
algorithm as the runtime's ``SigV4.scala``, over ``hashlib`` and ``hmac``.

Paths are encoded once, segment by segment, as S3 expects (other AWS services encode twice).
"""

from __future__ import annotations

import hashlib
import hmac
import re
from dataclasses import dataclass, field
from datetime import UTC, datetime
from urllib.parse import urlsplit

ALGORITHM = "AWS4-HMAC-SHA256"
UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
"""The payload hash a put sends so its body is streamed once and never hashed."""


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


EMPTY_PAYLOAD = sha256_hex(b"")
"""The hash of an empty payload."""


@dataclass(frozen=True)
class Credentials:
    """A key pair. Its ``repr`` never shows the secret."""

    access_key: str
    secret_key: str = field(repr=False)

    def __repr__(self) -> str:
        return f"Credentials({self.access_key}, ***)"

    __str__ = __repr__


_UNRESERVED = frozenset(b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~")


def encode(text: str, encode_slash: bool) -> str:
    """RFC 3986 encoding as SigV4 states it: unreserved characters kept, everything else ``%XX`` of
    its UTF-8 bytes; ``/`` kept unless ``encode_slash``."""
    out: list[str] = []
    for b in text.encode("utf-8"):
        if b in _UNRESERVED or (b == 0x2F and not encode_slash):
            out.append(chr(b))
        else:
            out.append(f"%{b:02X}")
    return "".join(out)


@dataclass(frozen=True)
class Request:
    """One request: the endpoint's scheme and authority, a decoded path and decoded query pairs."""

    method: str
    endpoint: str
    path: str
    query: tuple[tuple[str, str], ...] = ()

    @property
    def host(self) -> str:
        """The ``host`` header's value: the host, with the port when the endpoint names one."""
        parts = urlsplit(self.endpoint)
        host = parts.hostname or ""
        if ":" in host:  # an IPv6 literal keeps its brackets
            host = f"[{host}]"
        return host if parts.port is None else f"{host}:{parts.port}"

    @property
    def scheme(self) -> str:
        return urlsplit(self.endpoint).scheme

    @property
    def canonical_path(self) -> str:
        base = urlsplit(self.endpoint).path.rstrip("/")
        p = self.path if self.path.startswith("/") else "/" + self.path
        return base + encode(p, encode_slash=False)

    @property
    def canonical_query(self) -> str:
        pairs = sorted((encode(k, True), encode(v, True)) for k, v in self.query)
        return "&".join(f"{k}={v}" for k, v in pairs)

    @property
    def target(self) -> str:
        """The path and query sent on the request line: the same encoding as the one signed."""
        q = self.canonical_query
        return self.canonical_path + ("?" + q if q else "")

    @property
    def url(self) -> str:
        return f"{self.scheme}://{self.host}{self.target}"


def _amz_date(now: datetime) -> str:
    return now.astimezone(UTC).strftime("%Y%m%dT%H%M%SZ")


def _short_date(now: datetime) -> str:
    return now.astimezone(UTC).strftime("%Y%m%d")


def _hmac(key: bytes, data: str) -> bytes:
    return hmac.new(key, data.encode("utf-8"), hashlib.sha256).digest()


def _signing_key(secret: str, now: datetime, region: str, service: str) -> bytes:
    date = _hmac(("AWS4" + secret).encode("utf-8"), _short_date(now))
    return _hmac(_hmac(_hmac(date, region), service), "aws4_request")


def _string_to_sign(amz_date: str, scope: str, canonical_request: str) -> str:
    return f"{ALGORITHM}\n{amz_date}\n{scope}\n{sha256_hex(canonical_request.encode('utf-8'))}"


_SPACES = re.compile(" +")


def _canonical_headers(headers: dict[str, str]) -> tuple[str, str]:
    ordered = sorted((k, _SPACES.sub(" ", v.strip())) for k, v in headers.items())
    return ";".join(k for k, _ in ordered), "".join(f"{k}:{v}\n" for k, v in ordered)


def sign(
    request: Request,
    headers: dict[str, str],
    payload_hash: str,
    credentials: Credentials,
    region: str,
    service: str,
    now: datetime,
) -> dict[str, str]:
    """The headers to send with ``request`` so the store believes it: ``x-amz-date`` and
    ``Authorization``. ``headers`` are the ones to sign beside ``host`` and ``x-amz-date`` — an S3
    request includes ``x-amz-content-sha256`` among them — and are sent as given."""
    amz_date = _amz_date(now)
    signed = {k.lower(): v for k, v in headers.items()}
    signed["host"] = request.host
    signed["x-amz-date"] = amz_date
    names, canonical = _canonical_headers(signed)
    scope = f"{_short_date(now)}/{region}/{service}/aws4_request"
    creq = "\n".join(
        [request.method, request.canonical_path, request.canonical_query, canonical, names, payload_hash]
    )
    signature = hmac.new(
        _signing_key(credentials.secret_key, now, region, service),
        _string_to_sign(amz_date, scope, creq).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return {
        "x-amz-date": amz_date,
        "Authorization": f"{ALGORITHM} Credential={credentials.access_key}/{scope}, SignedHeaders={names}, Signature={signature}",
    }


def presign(
    request: Request,
    credentials: Credentials,
    region: str,
    service: str,
    expires_seconds: int,
    now: datetime,
) -> str:
    """A URL anyone may use, until it expires, for ``request``'s method on its path. Signs ``host``
    alone."""
    amz_date = _amz_date(now)
    scope = f"{_short_date(now)}/{region}/{service}/aws4_request"
    with_auth = Request(
        request.method,
        request.endpoint,
        request.path,
        request.query
        + (
            ("X-Amz-Algorithm", ALGORITHM),
            ("X-Amz-Credential", f"{credentials.access_key}/{scope}"),
            ("X-Amz-Date", amz_date),
            ("X-Amz-Expires", str(expires_seconds)),
            ("X-Amz-SignedHeaders", "host"),
        ),
    )
    creq = "\n".join(
        [request.method, with_auth.canonical_path, with_auth.canonical_query, f"host:{request.host}\n", "host", UNSIGNED_PAYLOAD]
    )
    signature = hmac.new(
        _signing_key(credentials.secret_key, now, region, service),
        _string_to_sign(amz_date, scope, creq).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return Request(with_auth.method, with_auth.endpoint, with_auth.path, with_auth.query + (("X-Amz-Signature", signature),)).url
