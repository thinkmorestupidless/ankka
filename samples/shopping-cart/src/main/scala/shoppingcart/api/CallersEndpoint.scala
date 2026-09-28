package shoppingcart.api

import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.{ServiceClients, ServiceUnresolvable}

/**
 * Who is calling, as the platform established it (feature 014). In a cluster every request arrives
 * over mutual TLS and the caller is read from the certificate the platform issued the calling
 * workload; on a laptop every caller is `Caller.Local`.
 *
 * Not part of the cart's domain: it is here so the platform's own suites and the documentation have
 * a real service to point at.
 */
final class CallersEndpoint(services: ServiceClients) extends HttpEndpoint("/callers"):

  val acl: Acl = Acl.AllowAll

  // docs:start who-is-calling
  get("/whoami") { () =>
    caller match
      case Caller.Gateway                => "the internet, through the gateway"
      case Caller.Service(project, name) => s"the $name service in project $project"
      case Caller.Local                  => "this machine"
  }
  // docs:end who-is-calling

  // docs:start allow-callers
  // Only the internet and the orders service in this project; any other caller is refused 403.
  withAcl(Acl.allowCallers(Callers.internet, Callers.service("orders"))) {
    get("/only-orders")(() => s"admitted: ${describe(caller)}")
  }

  // Another instance of this very service, and nothing else.
  withAcl(Acl.allowCallers(Callers.self)) {
    get("/only-self")(() => "admitted: myself")
  }
  // docs:end allow-callers

  // docs:start call-another-service
  // Calls `/callers/whoami` on another service in this project, as this service: the answer is
  // how that service saw this one.
  get("/call/{service}") { (service: String) =>
    try services(service).getText("/callers/whoami")
    catch case e: ServiceUnresolvable => throw HttpProblem(503, e.getMessage)
  }
  // docs:end call-another-service

  private def describe(c: Caller): String = c match
    case Caller.Service(project, name) => s"$project/$name"
    case other                         => other.toString.toLowerCase
