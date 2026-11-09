"""The endpoints of the documentation's HTTP samples: query parameters and headers read from the
request, an endpoint whose users sign in with an identity provider, and one endpoint with two
audiences, as the Scala ``QueryEndpoint``, ``AccountEndpoint`` and ``MixedAclEndpoint`` are."""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass

from ankka import Acl, Endpoint, HttpProblem, delete, get, sse


@dataclass(frozen=True)
class SearchResult:
    term: str
    limit: int
    tags: list[str]
    verbose: bool


class QueryEndpoint(Endpoint):
    """Exercises query parameters and headers from inside handlers."""

    prefix = "/search"
    acl = Acl.ALLOW_ALL

    # docs:start query
    @get("/")
    def search(self) -> SearchResult:
        """Required, optional-with-default, repeated, and flag parameters."""
        query = self.request
        term = query.query_param("q")
        if term is None:
            raise HttpProblem(400, "missing query parameter 'q'")
        limit = query.query_param("limit")
        return SearchResult(
            term=term,
            limit=int(limit) if limit is not None else 20,
            tags=query.query_params("tag"),
            verbose=query.query_param("verbose") in ("", "true"),
        )
    # docs:end query

    # docs:start header
    @get("/trace")
    def trace(self) -> str:
        """Headers."""
        return self.request.header("X-Trace-Id") or "none"
    # docs:end header

    # docs:start sse-query
    @sse("/stream")
    async def stream(self) -> AsyncIterator[str]:
        term = self.request.query_param("q") or ""
        count = int(self.request.query_param("count") or 2)
        for n in range(1, count + 1):
            yield f"{term}-{n}"
    # docs:end sse-query


# docs:start account
class AccountEndpoint(Endpoint):
    """An endpoint whose users sign in with an identity provider the service lists."""

    prefix = "/account"
    acl = Acl.AUTHENTICATED

    @get("/me")
    def me(self) -> str:
        principal = self.request.principal
        assert principal is not None  # the ACL established one before the handler ran
        return (
            f"{principal.subject} from {principal.issuer or '?'} "
            f"roles={','.join(sorted(principal.roles))} tier={principal.claims.get('tier', '')}"
        )
# docs:end account


# docs:start route-acl
class MixedAclEndpoint(Endpoint):
    """One endpoint, two audiences: reading a cart is public, purging one is not. A route's own
    ``acl`` replaces the endpoint's for that route, so neither audience needs an endpoint of its
    own at a second prefix."""

    prefix = "/mixed"
    acl = Acl.ALLOW_ALL

    @get("/{cartId}")
    def cart(self, cartId: str) -> str:
        return f"cart:{cartId}"

    @delete("/{cartId}", acl=Acl.AUTHENTICATED)
    def purge(self, cartId: str) -> str:
        principal = self.request.principal
        return f"purged:{cartId} by {principal.subject if principal else '?'}"
# docs:end route-acl
