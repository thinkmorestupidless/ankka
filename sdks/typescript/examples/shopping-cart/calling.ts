import { Acl, Callers, Endpoint, HttpProblem, ServiceUnresolvable, get, s } from "ankka"

// docs:start call-another-service
/**
 * Calls another service of this project, as this service: the answer is what that service answered,
 * and the service it called saw this one as the caller.
 */
export class CallingEndpoint extends Endpoint {
  static readonly prefix = "/calling"
  static readonly acl = Acl.allowCallers(Callers.self)

  static readonly routes = {
    call: get("/call/{service}", s.string, async (ep: CallingEndpoint, req) => {
      const path = req.query.get("path") ?? "/callers/whoami"
      try {
        return await ep.services.service(req.params.service).getText(path)
      } catch (e) {
        if (e instanceof ServiceUnresolvable) throw new HttpProblem(503, e.message)
        throw e
      }
    }),
  }
}
// docs:end call-another-service
