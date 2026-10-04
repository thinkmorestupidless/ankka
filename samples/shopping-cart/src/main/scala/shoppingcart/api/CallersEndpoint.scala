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

  // One service, by name, and nobody else — not the internet, not this service's own instances. A
  // request is admitted here only because its certificate names the orders service of this project.
  withAcl(Acl.allowCallers(Callers.service("orders"))) {
    get("/orders-alone")(() => s"admitted: ${describe(caller)}")
  }

  // docs:start call-another-service
  // Calls another service in this project, as this service: `/callers/whoami` unless `path` says
  // otherwise. The answer is what that service answered, and it saw this one as the caller.
  get("/call/{service}") { (service: String) =>
    val path = query.optional[String]("path").getOrElse("/callers/whoami")
    try services(service).getText(path)
    catch case e: ServiceUnresolvable => throw HttpProblem(503, e.getMessage)
  }
  // docs:end call-another-service

  private def describe(c: Caller): String = c match
    case Caller.Service(project, name) => s"$project/$name"
    case other                         => other.toString.toLowerCase
