"""An endpoint with socket routes, kept apart from the counter's so a service without one is still a
1.0 service to every other test."""

from __future__ import annotations

from ankka import Acl, Endpoint, Socket, socket


class RoomsEndpoint(Endpoint):
    prefix = "/rooms"
    acl = Acl.ALLOW_ALL

    @socket("/{room}")
    async def chat(self, room: str, socket: Socket) -> None:
        async for text in socket:
            if text == "context":
                await socket.send(f"{room} {self.request.query_param('tag') or ''}")
            else:
                await socket.send(text)

    @socket("/fail")
    async def fail(self, socket: Socket) -> None:
        await socket.receive()
        raise RuntimeError("the socket handler broke")
