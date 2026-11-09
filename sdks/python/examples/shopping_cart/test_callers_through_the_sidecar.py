"""A route's caller rules, applied by the sidecar itself: the integration kit names a caller the way
a certificate would in a cluster, so the test reads what the platform admitted, not what it stated."""

from __future__ import annotations

import pytest

from ankka import Ankka, Gateway, MachineCaller, ServiceCaller
from ankka.testkit.integration import AnkkaTestKit

from examples.shopping_cart.conformance import CallersEndpoint


@pytest.mark.slow
async def test_the_sidecar_admits_the_caller_the_kit_names_and_refuses_the_rest() -> None:
    async with await AnkkaTestKit.start(Ankka.service().register(CallersEndpoint)) as kit:

        async def whoami(caller: object | None) -> tuple[int, str]:
            headers = kit.caller_headers(caller) if caller is not None else {}  # type: ignore[arg-type]
            r = await kit.http.get("/callers/whoami", headers=headers)
            return r.status_code, r.text

        # The endpoint admits the internet (a machine arrives through it too) and this project's
        # orders service; any other service is refused.
        assert await whoami(Gateway()) == (200, "gateway")
        assert await whoami(MachineCaller("acme", "nightly")) == (200, "machine:acme/nightly")
        assert await whoami(ServiceCaller("local", "orders")) == (200, "service:local/orders")
        assert (await whoami(ServiceCaller("local", "billing")))[0] == 403
        # Without the header, or with a token the sidecar does not hold, the caller is the machine.
        assert await whoami(None) == (200, "local")
        forged = await kit.http.get("/callers/whoami", headers={"X-Ankka-Local-Caller": "not-the-token service:local/billing"})
        assert (forged.status_code, forged.text) == (200, "local")
