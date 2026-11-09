"""Who is calling, as the handler reads it: the callers endpoint's routes and the wallet another
project may be granted, against the endpoint test kit, which states the caller the sidecar would
have admitted."""

from __future__ import annotations

from ankka import ErrorCode, Gateway, MachineCaller, ServiceCaller
from ankka._proto.ankka.protocol.v1 import client_pb2, payload_pb2
from ankka.client import ComponentClient
from ankka.codec import default_codec_for
from ankka.testkit import EndpointTestKit, EventSourcedTestKit

from examples.shopping_cart.conformance import CallersEndpoint
from examples.shopping_cart.wallet import Deposit, DepositReply, Deposited, WalletEndpoint, WalletEntity


# docs:start test-as-caller
def test_the_handler_reads_the_caller_the_platform_established() -> None:
    kit = EndpointTestKit.of(CallersEndpoint)
    assert kit.get("/callers/whoami", caller=ServiceCaller("local", "orders")).text() == "service:local/orders"
    assert kit.get("/callers/whoami", caller=Gateway()).text() == "gateway"
    assert kit.get("/callers/whoami", caller=MachineCaller("acme", "nightly")).text() == "machine:acme/nightly"
    # Outside a cluster every caller is the local machine.
    assert kit.get("/callers/whoami").text() == "local"
# docs:end test-as-caller


def test_a_deposit_is_applied_once_per_key() -> None:
    kit = EventSourcedTestKit.of(WalletEntity, "p1")
    first = kit.call("deposit", Deposit("k-1", "EUR", 50))
    assert first.events == (Deposited("k-1", "EUR", 50),) and first.reply == DepositReply("EUR", 50, True)
    again = kit.call("deposit", Deposit("k-1", "EUR", 50))
    assert again.events == () and again.reply == DepositReply("EUR", 50, False)
    refused = kit.call("deposit", Deposit("k-2", "EUR", 0))
    assert refused.error is not None and refused.error.code == ErrorCode.BAD_REQUEST
    assert kit.call("get").reply.balance("EUR") == 50


class _Sidecar:
    """Answers every deposit as applied, with the amount as the balance."""

    async def Invoke(self, request: client_pb2.InvokeRequest) -> client_pb2.InvokeReply:  # noqa: N802
        deposit = default_codec_for(Deposit).decode(request.payload.data)
        reply = DepositReply(deposit.currency, deposit.amount, True)
        payload = payload_pb2.Payload(content_type="application/json", manifest="DepositReply", data=default_codec_for(DepositReply).encode(reply))
        return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply(payload=payload))


def test_a_granted_caller_deposits_with_its_idempotency_key() -> None:
    client = ComponentClient(address="127.0.0.1:0")
    client._stub_cache = _Sidecar()  # type: ignore[assignment]
    kit = EndpointTestKit.of(WalletEndpoint, client)
    made = kit.post("/v1/wallets/p1/EUR/deposits", {"amount": 25}, headers=[("Idempotency-Key", "k-9")], caller=ServiceCaller("casino", "lobby"))
    assert made.json() == {"currency": "EUR", "balance": 25, "applied": True, "by": "service:casino/lobby"}
    without = kit.post("/v1/wallets/p1/EUR/deposits", {"amount": 25})
    assert without.status == 400 and "Idempotency-Key" in without.text()
