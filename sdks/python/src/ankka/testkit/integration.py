"""The integration testkit: this process, a real sidecar, a real Postgres.

Starts Postgres with the platform's schema (copied out of the sidecar image, so a test can never
pass against a schema the platform does not have), the ``ankka-sidecar`` image pointed at this
process through ``host.docker.internal``, and this process's gRPC server. ``http`` talks to the
sidecar's HTTP port, where the declared routes are served; ``restart`` replaces the sidecar
container against the same database, which is how a test proves durability rather than caching.

Needs Docker. The image is ``$ANKKA_SIDECAR_IMAGE`` when that is set. Otherwise a released SDK uses the
sidecar published with it, ``ghcr.io/thinkmorestupidless/ankka-sidecar:<this SDK's version>`` — public,
so Docker pulls it on first use — and an unreleased one (version ``0.0.0``, a checkout of the ankka
repository) uses ``ankka-sidecar:latest``, the image ``sbt sidecar/Docker/publishLocal`` builds.
"""

from __future__ import annotations

from collections.abc import AsyncIterator

import asyncio
import base64
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any

import grpc
import httpx
from testcontainers.core.container import DockerContainer
from testcontainers.core.network import Network
from testcontainers.community.postgres import PostgresContainer

from ankka.autonomous import Notification, TaskSnapshot, TaskType
from ankka.client import ComponentClient
from ankka.server import Server
from ankka.service import ServiceBuilder
from ankka.storage import STORAGE_BACKEND, Buckets, from_environment

POSTGRES_IMAGE = "postgres:17-alpine"
HTTP_PORT = 9000
CALLBACK_PORT = 9011
# The sidecar's local console endpoint, where ``kit.keyring`` files erasures and outages: named with
# ANKKA_OBSERVABILITY_ADDRESS so Docker can publish it, where a service run on a machine binds it to
# loopback on an ephemeral port.
LOCAL_ENDPOINT_PORT = 9012


def personal_value_query(values: tuple[str, ...]) -> str:
    """One statement that searches every column of every table of the service's database, cast to
    text, for each value as written, as base64 (standard and URL-safe, unpadded) and as lower-case
    hex in the lower-cased text: the rules of the JVM kit's ``PersonalAssertions``, the same forms.
    Each row is ``<index of the value> <form> <schema>.<table>.<column>``."""
    rows = []
    for i, value in enumerate(values):
        data = value.encode("utf-8")
        forms = {
            "plain": value,
            "base64": base64.b64encode(data).decode("ascii").rstrip("="),
            "base64url": base64.urlsafe_b64encode(data).decode("ascii").rstrip("="),
            "hex": data.hex(),
        }
        for name, form in forms.items():
            rows.append(f"({i}, '{name}', '{form.replace(chr(39), chr(39) * 2)}')")
    return (
        "SELECT f.i || ' ' || f.form_name || ' ' || c.table_schema || '.' || c.table_name || '.' || c.column_name "
        "FROM information_schema.columns c CROSS JOIN (VALUES " + ", ".join(rows) + ") AS f(i, form_name, form) "
        "WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema') "
        "AND (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I.%I WHERE strpos(%s, %L) > 0', "
        "c.table_schema, c.table_name, "
        "CASE WHEN f.form_name = 'hex' THEN format('lower(%I::text)', c.column_name) ELSE format('%I::text', c.column_name) END, "
        "f.form), false, true, '')))[1]::text::int > 0 "
        "ORDER BY c.table_schema, c.table_name, c.ordinal_position, f.i"
    )


def personal_value_failure(values: tuple[str, ...], output: str) -> str | None:
    """The JVM kit's failure message for what ``personal_value_query`` found, or None for nothing."""
    found = []
    for line in output.splitlines():
        if not line.strip():
            continue
        i, form, where = line.split(" ", 2)
        found.append(f"  '{values[int(i)]}' as {form} in {where}")
    if not found:
        return None
    return "a personal value can be read back from what the service kept:\n" + "\n".join(found)


class KitKeyring:
    """The keyring of the kit's sidecar (feature 042): the runtime's local keyring, kept in the kit's
    database, which these reach through the sidecar's local console endpoint. ``erase`` destroys a
    data subject's key at once and sends the notice, as an applied erasure request does; ``outage``
    makes the keyring refuse every request as unavailable, erasures included, until it ends or
    ``recover`` ends it. An outage is the sidecar's: ``kit.restart()`` ends it."""

    def __init__(self, kit: AnkkaTestKit) -> None:
        self._kit = kit

    async def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        async with httpx.AsyncClient(base_url=self._kit._local_url, timeout=30.0) as http:
            answer = await http.post(path, json=body)
        if answer.status_code != 200:
            raise RuntimeError(f"{path} answered {answer.status_code}: {answer.text}")
        result: dict[str, Any] = answer.json()
        return result

    async def erase(self, subject: str, rerun: bool = False) -> int:
        """Erases ``subject`` in the service's project now; answers the notice's sequence once the
        sidecar has carried it out (or after ten seconds). Raises in an outage."""
        return int((await self._post("/_ankka/erasures", {"subject": subject, "rerun": rerun}))["seq"])

    async def outage(self, seconds: float) -> None:
        """Refuses every request as unavailable for ``seconds``: a subject whose key the sidecar
        holds is still read and written; one it does not hold is refused, ``ErrorCode.UNAVAILABLE``."""
        if seconds <= 0:
            raise ValueError("an outage lasts more than 0 seconds; recover() ends one")
        await self._post("/_ankka/keyring/outage", {"seconds": seconds})

    async def recover(self) -> None:
        """Ends an outage now."""
        await self._post("/_ankka/keyring/outage", {"seconds": 0})


PUBLISHED_SIDECAR = "ghcr.io/thinkmorestupidless/ankka-sidecar"


def sidecar_image(version: str | None = None) -> str:
    """The sidecar this testkit starts: see the module's docstring. A release publishes the SDK and the
    sidecar under one version, so a released SDK's own version names the sidecar it was tested with."""
    explicit = os.environ.get("ANKKA_SIDECAR_IMAGE")
    if explicit:
        return explicit
    if version is None:
        from ankka import __version__ as version
    return "ankka-sidecar:latest" if version == "0.0.0" else f"{PUBLISHED_SIDECAR}:{version}"


def _copy_ddl(image: str, into: Path) -> None:
    """The DDL as files, from ``/opt/docker/ddl`` in the sidecar image."""
    container_id = subprocess.check_output(["docker", "create", image], text=True).strip()
    try:
        subprocess.check_call(["docker", "cp", f"{container_id}:/opt/docker/ddl/.", str(into)])
    finally:
        subprocess.call(["docker", "rm", "-f", container_id], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _start_or_explain(container: DockerContainer, what: str) -> None:
    """Start a container, and if it does not come up, fail with its own logs.

    A readiness wait that times out reports only the last probe's failure: a container that exited
    during its own startup reads as "not running", and why is in a log nothing prints. The reason
    belongs in the error.
    """
    try:
        container.start()
    except Exception as failure:
        try:
            out, err = container.get_logs()
            logs = (out + err).decode(errors="replace").strip()
        except Exception:  # noqa: BLE001 - the container may be gone; the original error still stands
            logs = "(no logs could be read)"
        raise RuntimeError(f"{what} did not start: {failure}\n--- container logs ---\n{logs}") from failure


class AnkkaTestKit:
    def __init__(
        self, service: ServiceBuilder, image: str, env: dict[str, str] | None = None, storage: Buckets | None = None
    ) -> None:
        self.service = service
        self.image = image
        self.env = env or {}
        # The service's buckets: chosen as the process would choose them with ANKKA_STORAGE_BACKEND
        # set to memory — the buckets the service.json beside the test declares, else the unnamed
        # one — unless the test hands its own over. The same store the handlers hold, so a test
        # reads what a handler kept, and it outlives `restart`, which replaces only the sidecar.
        self.storage: Buckets = (
            storage if storage is not None else from_environment({**os.environ, STORAGE_BACKEND: "memory"})
        )
        self._network: Network | None = None
        self._postgres: PostgresContainer | None = None
        self._sidecar: DockerContainer | None = None
        self._server: Server | None = None
        self._ddl_dir: Path | None = None
        self.client = ComponentClient(address="127.0.0.1:0")
        self.http: httpx.AsyncClient = httpx.AsyncClient()
        self.process_port = 0
        self._local_url = ""
        # The sidecar's keyring: erase a data subject, start or end an outage (feature 042).
        self.keyring = KitKeyring(self)

    @classmethod
    async def start(
        cls,
        service: ServiceBuilder,
        image: str | None = None,
        ready_timeout: float = 90.0,
        env: dict[str, str] | None = None,
        storage: Buckets | None = None,
    ) -> AnkkaTestKit:
        """``env`` goes onto the sidecar container: ``ANKKA_MODEL_SCRIPT`` scripts its model.
        ``storage`` is the service's buckets, ``kit.storage``; by default a store in memory."""
        kit = cls(service, image or sidecar_image(), env, storage)
        try:
            await kit._start(ready_timeout)
        except BaseException:
            # A start that fails half-way has already begun this process's gRPC server and started
            # containers; nothing else will stop them, since the caller never gets a kit to exit.
            # Left running, the server keeps the interpreter alive after pytest has finished and
            # the containers stay up until it dies.
            await kit.stop()
            raise
        return kit

    async def __aenter__(self) -> AnkkaTestKit:
        return self

    async def __aexit__(self, *_: Any) -> None:
        await self.stop()

    # ── lifecycle ─────────────────────────────────────────────────────────

    async def _start(self, ready_timeout: float) -> None:
        self._ddl_dir = Path(tempfile.mkdtemp(prefix="ankka-ddl-"))
        # mkdtemp creates the directory with mode 0700, and the container reads it as its own
        # `postgres` user (uid 70), not as the user who created it. On Linux a bind mount keeps the
        # host's permissions, so the entrypoint's `ls /docker-entrypoint-initdb.d/` (which it runs
        # under `set -e` before initdb, as a permissions check) fails and the container exits before
        # it ever listens. Docker Desktop on macOS maps ownership through its file sharing, which is
        # why this passes on a laptop and failed on every CI run.
        self._ddl_dir.chmod(0o755)
        _copy_ddl(self.image, self._ddl_dir)
        self._network = Network()
        self._network.create()
        self._postgres = (
            PostgresContainer(POSTGRES_IMAGE, username="ankka", password="ankka", dbname="ankka")
            .with_network(self._network)
            .with_network_aliases("postgres")
            .with_volume_mapping(str(self._ddl_dir), "/docker-entrypoint-initdb.d", "ro")
        )
        _start_or_explain(self._postgres, "postgres")
        # This process's server, on all interfaces: the sidecar is in a container and dials in.
        self._server = Server(self.service.validate(), client=self.client, storage=self.storage)
        self.process_port = await self._server.start("0.0.0.0", 0)
        await self._start_sidecar(ready_timeout)

    async def _start_sidecar(self, ready_timeout: float) -> None:
        assert self._network is not None
        sidecar = (
            DockerContainer(self.image)
            .with_network(self._network)
            .with_env("ANKKA_PROCESS_ADDRESS", f"host.docker.internal:{self.process_port}")
            .with_env("ANKKA_SIDECAR_BIND", "0.0.0.0")
            .with_env("ANKKA_HTTP_PORT", str(HTTP_PORT))
            .with_env("ANKKA_DB_HOST", "postgres")
            .with_env("ANKKA_DB_PORT", "5432")
            .with_env("ANKKA_DB_NAME", "ankka")
            .with_env("ANKKA_DB_USER", "ankka")
            .with_env("ANKKA_DB_PASSWORD", "ankka")
            # A fresh secret key per kit, so the secret store works with no setup; a caller's
            # `env` replaces it, and `{"ANKKA_SECRET_KEY": ""}` starts a service with none.
            .with_env("ANKKA_SECRET_KEY", base64.b64encode(os.urandom(32)).decode("ascii"))
            .with_env("ANKKA_OBSERVABILITY_ADDRESS", f"0.0.0.0:{LOCAL_ENDPOINT_PORT}")
            .with_exposed_ports(HTTP_PORT, CALLBACK_PORT, LOCAL_ENDPOINT_PORT)
            .with_kwargs(extra_hosts={"host.docker.internal": "host-gateway"})
        )
        for key, value in self.env.items():
            sidecar = sidecar.with_env(key, value)
        _start_or_explain(sidecar, "the sidecar")
        self._sidecar = sidecar
        http_port = int(sidecar.get_exposed_port(HTTP_PORT))
        callback_port = int(sidecar.get_exposed_port(CALLBACK_PORT))
        self._local_url = f"http://127.0.0.1:{sidecar.get_exposed_port(LOCAL_ENDPOINT_PORT)}"
        self.client.reconnect(f"127.0.0.1:{callback_port}")
        self.http = httpx.AsyncClient(base_url=f"http://127.0.0.1:{http_port}", timeout=30.0)
        await self._wait_ready(ready_timeout, f"127.0.0.1:{callback_port}")

    async def _wait_ready(self, timeout: float, callback_address: str) -> None:
        """Ready means both of the sidecar's ports serve: HTTP for the test, and the callback port
        every handler's component client calls. The health route answers as soon as HTTP is bound,
        which can be before the callback server is, and Docker's port proxy accepts a connection to
        a port nothing inside the container listens on yet, then resets it — so a handler's first
        call after a restart failed UNAVAILABLE. A gRPC channel is READY only once the real server
        has completed the HTTP/2 handshake."""
        deadline = time.monotonic() + timeout
        last: str = "no answer yet"
        healthy = False
        while time.monotonic() < deadline:
            if not healthy:
                try:
                    r = await self.http.get("/_ankka/health")
                    healthy = r.status_code == 200
                    last = f"HTTP {r.status_code}"
                except Exception as e:  # not up yet
                    last = str(e)
            if healthy:
                channel = grpc.aio.insecure_channel(callback_address)
                try:
                    await asyncio.wait_for(channel.channel_ready(), timeout=2.0)
                    return
                except asyncio.TimeoutError:
                    last = f"HTTP answered, the callback port {callback_address} did not"
                finally:
                    await channel.close()
            await asyncio.sleep(0.5)
        logs = self._sidecar.get_logs() if self._sidecar is not None else (b"", b"")
        # A non-200 *answer* here is almost never the sidecar's: its health route answers 200 from
        # the moment HTTP is bound. On a laptop it is another process that already held the host
        # port Docker mapped the container to, bound on 127.0.0.1 and so answering before Docker's
        # own listener does.
        raise TimeoutError(
            f"the sidecar was not ready within {timeout}s ({last} from {self.http.base_url}); its log:\n"
            + logs[0].decode(errors="replace")[-4000:]
        )

    async def restart(self, ready_timeout: float = 90.0) -> None:
        """Replaces the sidecar container against the same database: every instance is gone from
        memory, so the next read has to rebuild from the journal."""
        if self._sidecar is not None:
            self._sidecar.stop()
            self._sidecar = None
        await self.http.aclose()
        await self._start_sidecar(ready_timeout)

    async def stop(self) -> None:
        if self._server is not None:
            await self._server.stop()
            self._server = None
        await self.http.aclose()
        if self._sidecar is not None:
            self._sidecar.stop()
            self._sidecar = None
        if self._postgres is not None:
            self._postgres.stop()
            self._postgres = None
        if self._network is not None:
            self._network.remove()
            self._network = None
        if self._ddl_dir is not None:
            shutil.rmtree(self._ddl_dir, ignore_errors=True)
            self._ddl_dir = None

    # ── other processes on the same database ─────────────────────────────

    def start_beside(self, image: str, http_port: int = HTTP_PORT, env: dict[str, str] | None = None) -> tuple[DockerContainer, httpx.AsyncClient]:
        """Starts another image against this kit's Postgres — an in-process Scala service, say, to
        prove the journal is shared. The caller stops the container."""
        assert self._network is not None
        container = (
            DockerContainer(image)
            .with_network(self._network)
            .with_env("ANKKA_HTTP_PORT", str(http_port))
            .with_env("ANKKA_DB_HOST", "postgres")
            .with_env("ANKKA_DB_PORT", "5432")
            .with_env("ANKKA_DB_NAME", "ankka")
            .with_env("ANKKA_DB_USER", "ankka")
            .with_env("ANKKA_DB_PASSWORD", "ankka")
            .with_exposed_ports(http_port)
        )
        for k, v in (env or {}).items():
            container = container.with_env(k, v)
        container.start()
        port = int(container.get_exposed_port(http_port))
        return container, httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}", timeout=30.0)

    @staticmethod
    async def wait_healthy(http: httpx.AsyncClient, timeout: float = 90.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                if (await http.get("/_ankka/health")).status_code == 200:
                    return
            except Exception:
                pass
            await asyncio.sleep(0.5)
        raise TimeoutError("the service was not healthy in time")

    # ── what a test reads ─────────────────────────────────────────────────

    async def await_task(self, task_id: str, task_type: TaskType[Any] | None = None, timeout: float = 30.0) -> TaskSnapshot[Any]:
        """Waits until an autonomous agent's task has ended, answering its record with the result
        decoded as ``task_type``'s. Fails naming where the task had got to, and with the sidecar's
        recent log, when it has not ended in time."""
        try:
            return await self.client.for_task(task_id).wait(task_type, timeout)
        except TimeoutError as e:
            raise AssertionError(f"{e}\n{self.sidecar_logs()[-3000:]}") from e

    def notifications(self, component_id: str, instance_id: str) -> AsyncIterator[Notification]:
        """What an autonomous agent instance does from now on, as it happens."""
        return self.client.for_autonomous_agent(component_id, instance_id).notifications()

    async def assert_no_personal_value(self, *values: str) -> None:
        """Fails, naming each table and column, when any of ``values`` can be read back from the
        service's database as written, as base64 (standard and URL-safe) or as hex: what a personal
        field must never be (feature 042, FR-030). Every column of every table, cast to text, searched
        by the kit's Postgres itself with the JVM kit's rules (``personal_value_query``)."""
        if not values:
            return
        assert self._postgres is not None, "the testkit is not started"
        postgres = self._postgres
        command = ["psql", "-U", "ankka", "-d", "ankka", "-v", "ON_ERROR_STOP=1", "-At", "-c", personal_value_query(values)]
        code, output = await asyncio.to_thread(lambda: postgres.exec(command))
        text = output.decode(errors="replace") if isinstance(output, bytes) else str(output)
        if code != 0:
            raise RuntimeError(f"the database could not be searched: {text}")
        failure = personal_value_failure(values, text)
        if failure is not None:
            raise AssertionError(failure)

    def sidecar_logs(self) -> str:
        if self._sidecar is None:
            return ""
        out, err = self._sidecar.get_logs()
        return (out + err).decode(errors="replace")

    @property
    def jdbc_url(self) -> str:
        assert self._postgres is not None
        return self._postgres.get_connection_url()
