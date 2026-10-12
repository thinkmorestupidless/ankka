"""What a project must know about a topic a component reads or publishes to: the contract it
states, the declared broker the topic is on, and whether its partitions are read in parallel."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import rfc8785

from ankka._proto.ankka.protocol.v1 import discovery_pb2

# The first protocol in which a process can state any of these; an older sidecar would ignore them.
CONTRACT_PROTOCOL = (1, 14)

NAME_RULE = re.compile(r"[a-z0-9][a-z0-9._-]{0,98}[a-z0-9]")


@dataclass(frozen=True)
class Contract:
    """A name and the fingerprint of the schema document a component was built against.

    A project declares a contract on a topic with the document; a member fetches the document and
    keeps it in the project; the component names it here. The runtime refuses, at start, a side
    whose name or fingerprint is not the declared one. The fingerprint is ``sha256:`` and the hex
    SHA-256 of the document under RFC 8785, so key order and whitespace in the saved file do not
    change it and a changed field does. Nothing checks a message against the schema as it flows."""

    name: str
    fingerprint: str

    @staticmethod
    def from_bytes(document: bytes, *, name: str) -> Contract:
        """The contract of a schema document held as bytes, under ``name``."""
        if not NAME_RULE.fullmatch(name):
            raise ValueError(f"contract name '{name}' is not {NAME_RULE.pattern}")
        try:
            # Every JSON number is an IEEE double under RFC 8785, as it is in every other SDK; an
            # integer past 2^53 is the double it rounds to, not the integer Python would keep.
            parsed = json.loads(document, parse_int=float)
        except ValueError as failure:
            raise ValueError(f"the schema of '{name}' is not JSON: {failure}") from None
        digest = hashlib.sha256(rfc8785.dumps(parsed)).hexdigest()
        return Contract(name, f"sha256:{digest}")

    @staticmethod
    def from_file(path: str | Path, *, name: str) -> Contract:
        """The contract of the schema document at ``path``, fetched from the project with
        ``ankka projects topics schema get`` and kept beside the code built against it."""
        return Contract.from_bytes(Path(path).read_bytes(), name=name)

    def to_pb(self) -> discovery_pb2.Contract:
        return discovery_pb2.Contract(name=self.name, fingerprint=self.fingerprint)


@dataclass(frozen=True)
class Publication:
    """A topic a consumer publishes to, with the contract it states for it and the declared broker
    it is on. ``produces_to = "orders"`` is the short form: the topic alone."""

    topic: str
    contract: Contract | None = None
    broker: str | None = None
    # Another project's topic (1.15), which that project must grant this service produce on.
    project: str | None = None

    def to_pb(self) -> discovery_pb2.Publication:
        pb = discovery_pb2.Publication(topic=self.topic)
        if self.contract is not None:
            pb.contract.CopyFrom(self.contract.to_pb())
        if self.broker is not None:
            pb.broker = self.broker
        if self.project is not None:
            pb.project = self.project
        return pb


def publication_of(cls: type) -> Publication | None:
    """What ``cls`` publishes to, whether it said a topic or a ``Publication``."""
    declared: Any = getattr(cls, "produces_to", None)
    if declared is None:
        return None
    if isinstance(declared, Publication):
        return declared
    return Publication(str(declared))


def problems(cls: type) -> list[str]:
    """What is wrong with the contract, broker and parallel ``cls`` declares, naming ``cls``."""
    found: list[str] = []
    name = cls.__name__
    reads_topic = getattr(cls, "source", None) is None and getattr(cls, "topic", None) is not None
    contract = getattr(cls, "contract", None)
    broker = getattr(cls, "broker", None)
    parallel = getattr(cls, "parallel", False)
    if contract is not None and not isinstance(contract, Contract):
        found.append(f"{name}.contract must be a Contract, from Contract.from_file(...)")
    if broker is not None and not isinstance(broker, str):
        found.append(f"{name}.broker must be the name of a broker the project declares")
    if not isinstance(parallel, bool):
        found.append(f"{name}.parallel must be True or False")
    if (contract is not None or broker is not None or parallel) and not reads_topic:
        found.append(f"{name} declares a contract, a broker or parallel, which apply to a topic; it reads a component")
    declared = getattr(cls, "produces_to", None)
    if declared is not None and not isinstance(declared, (str, Publication)):
        found.append(f"{name}.produces_to must be a topic name or a Publication")
    project = getattr(cls, "project", None)
    if project is not None and not isinstance(project, str):
        found.append(f"{name}.project must be the id of the project whose topic this is")
    if project is not None and not reads_topic:
        found.append(f"{name} names a project, which applies to a topic; it reads a component")
    if project is not None and broker is not None:
        found.append(f"{name} names a project and a broker; another project's topic is on the installation's broker")
    if isinstance(declared, Publication) and declared.project is not None and declared.broker is not None:
        found.append(f"{name}.produces_to names a project and a broker; another project's topic is on the installation's broker")
    return found


def declares_cross_project(cls: type) -> bool:
    """Whether ``cls`` reads or publishes to another project's topic, which a runtime older than
    1.15 would read as this project's."""
    declared = getattr(cls, "produces_to", None)
    return getattr(cls, "project", None) is not None or (
        isinstance(declared, Publication) and declared.project is not None
    )


def declares_any(cls: type) -> bool:
    """Whether ``cls`` says something a sidecar older than 1.14 would ignore."""
    return (
        getattr(cls, "contract", None) is not None
        or getattr(cls, "broker", None) is not None
        or bool(getattr(cls, "parallel", False))
        or isinstance(getattr(cls, "produces_to", None), Publication)
    )


def apply(source: discovery_pb2.Source, cls: type) -> discovery_pb2.Source:
    """Writes what ``cls`` declares about its topic into ``source``; nothing when it declares nothing."""
    contract: Any = getattr(cls, "contract", None)
    if isinstance(contract, Contract):
        source.contract.CopyFrom(contract.to_pb())
    broker: Any = getattr(cls, "broker", None)
    if isinstance(broker, str):
        source.broker = broker
    if getattr(cls, "parallel", False) is True:
        source.parallel = True
    project: Any = getattr(cls, "project", None)
    if isinstance(project, str):
        source.project = project
    return source
