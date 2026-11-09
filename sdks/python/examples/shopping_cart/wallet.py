"""A player's wallet, which another project may be granted. The entity makes a retried deposit safe
by the idempotency key each one carries; the endpoint admits granted callers."""

from __future__ import annotations

from dataclasses import dataclass, field, replace

from ankka import (
    Acl,
    Callers,
    EventSourcedEffect,
    EventSourcedEntity,
    HttpProblem,
    MachineCaller,
    ReadOnlyEffect,
    ServiceCaller,
    Endpoint,
    Gateway,
    command,
    json_codec,
    post,
    query,
)
from ankka.client import Calls, ComponentClient
from ankka.context import Caller


@dataclass(frozen=True)
class Wallet:
    """A balance per currency, and the idempotency keys of every deposit applied."""

    player: str
    balances: dict[str, int] = field(default_factory=dict)
    applied: list[str] = field(default_factory=list)

    def balance(self, currency: str) -> int:
        return self.balances.get(currency, 0)

    def on_deposited(self, key: str, currency: str, amount: int) -> Wallet:
        balances = {**self.balances, currency: self.balance(currency) + amount}
        return replace(self, balances=balances, applied=[*self.applied, key])


@dataclass(frozen=True)
class Deposited:
    key: str
    currency: str
    amount: int


@dataclass(frozen=True)
class Deposit:
    """A deposit as the wallet's command takes it: the key, the currency and the amount."""

    key: str
    currency: str
    amount: int


@dataclass(frozen=True)
class DepositRequest:
    """A deposit's body on the wallet's route; the key arrives as the `Idempotency-Key` header."""

    amount: int


@dataclass(frozen=True)
class DepositReply:
    currency: str
    balance: int
    applied: bool


@dataclass(frozen=True)
class DepositMade:
    """What the deposit route answers: the wallet's reply, and the caller the platform established."""

    currency: str
    balance: int
    applied: bool
    by: str


class WalletEntity(EventSourcedEntity[Wallet, Deposited]):
    component_id = "wallet"
    state_codec = json_codec(Wallet, "wallet")
    event_codec = json_codec(Deposited, "wallet-event")

    def empty_state(self) -> Wallet:
        return Wallet(self.entity_id)

    def apply_event(self, state: Wallet, event: Deposited) -> Wallet:
        return state.on_deposited(event.key, event.currency, event.amount)

    # docs:start idempotent-deposit
    @command("deposit")
    def deposit(self, request: Deposit) -> EventSourcedEffect[Wallet, Deposited, DepositReply]:
        if not request.key:
            return self.effects.error("a deposit needs an idempotency key")
        if request.amount <= 0:
            return self.effects.error(f"amount must be positive, was {request.amount}")
        if request.key in self.state.applied:
            return self.effects.reply(DepositReply(request.currency, self.state.balance(request.currency), False))
        return self.effects.persist(Deposited(request.key, request.currency, request.amount)).then_reply(
            lambda w: DepositReply(request.currency, w.balance(request.currency), True)
        )
    # docs:end idempotent-deposit

    @query("get")
    def get(self) -> ReadOnlyEffect[Wallet, Deposited, Wallet]:
        return self.effects.reply(self.state)


def who(caller: Caller) -> str:
    if isinstance(caller, ServiceCaller):
        return f"service:{caller.project}/{caller.name}"
    if isinstance(caller, MachineCaller):
        return f"machine:{caller.organization}/{caller.name}"
    return "gateway" if isinstance(caller, Gateway) else "local"


# docs:start granted-callers
class WalletEndpoint(Endpoint):
    """A player's wallet, which another project may be granted. The endpoint's ACL admits granted
    callers: whether anyone outside the project may ever call these routes is decided here, in
    reviewed code; who may, today, is a grant the project's owners make and revoke as data."""

    prefix = "/v1/wallets"
    acl = Acl.allow_callers(Callers.granted)

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    @post("/{player}/{currency}/deposits")
    async def deposit(self, player: str, currency: str, body: DepositRequest) -> DepositMade:
        """A deposit, with the caller's idempotency key: a granted call is delivered at least once,
        and the wallet applies each key once. The answer names the caller as the platform established it."""
        key = self.request.header("Idempotency-Key")
        if key is None:
            raise HttpProblem(400, "a deposit needs an Idempotency-Key header")
        reply = await self._wallet(player).call("deposit").invoke(Deposit(key, currency, body.amount), reply=DepositReply)
        return DepositMade(reply.currency, reply.balance, reply.applied, who(self.request.caller))
    # docs:end granted-callers

    def _wallet(self, player: str) -> Calls:
        return self.client.with_metadata(self.request.metadata).for_event_sourced_entity("wallet", player)
