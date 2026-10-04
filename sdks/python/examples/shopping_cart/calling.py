from __future__ import annotations

from ankka import Acl, Callers, Endpoint, HttpProblem, ServiceUnresolvable, get


# docs:start call-another-service
class CallingEndpoint(Endpoint):
    """Calls another service of this project, as this service: the answer is what that service
    answered, and the service it called saw this one as the caller."""

    prefix = "/calling"
    acl = Acl.allow_callers(Callers.self_)

    @get("/call/{service}")
    async def call(self, service: str) -> str:
        path = self.request.query_param("path") or "/callers/whoami"
        try:
            return await self.services(service).get_text(path)
        except ServiceUnresolvable as unresolvable:
            raise HttpProblem(503, str(unresolvable)) from unresolvable
# docs:end call-another-service
