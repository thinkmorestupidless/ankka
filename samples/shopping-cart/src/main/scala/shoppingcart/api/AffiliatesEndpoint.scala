package shoppingcart.api

import com.thinkmorestupidless.ankka.http.{Acl, Callers, HttpEndpoint}

/**
 * What an affiliate platform outside the installation reads (feature 040): attribution, to a
 * machine granted it; a feed, to anyone; and a report, to whoever holds a token from an issuer the
 * service lists. A registered machine's token is believed on every route, so the report sees a
 * machine as its principal too.
 */
final class AffiliatesEndpoint(authenticate: Option[Acl]) extends HttpEndpoint("/v1/affiliates"):

  val acl: Acl = Acl.allowCallers(Callers.granted)

  get("/attribution")(() => s"attribution for ${Who(caller)}")

  withAcl(Acl.AllowAll) {
    get("/feed")(() => s"feed for ${Who(caller)}")
  }

  authenticate.foreach { acl =>
    withAcl(acl) {
      get("/report")(() => s"report for ${principal.subject} as ${Who(caller)}")
    }
  }
