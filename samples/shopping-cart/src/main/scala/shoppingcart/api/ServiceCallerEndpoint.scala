package shoppingcart.api

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.SessionId
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import shoppingcart.application.ServiceCaller

import java.util.UUID

/**
 * Asks [[ServiceCaller]] to call another service: `GET /agent/call/{service}?path=/callers/whoami`
 * answers what that service answered to the agent's tool. Each request is a session of its own.
 */
final class ServiceCallerEndpoint(client: ComponentClient) extends HttpEndpoint("/agent"):

  val acl: Acl = Acl.AllowAll

  get("/call/{service}") { (service: String) =>
    val path = query.optional[String]("path").getOrElse("/callers/whoami")
    client
      .forAgent(SessionId(UUID.randomUUID().toString))
      .call(ServiceCaller.ask)
      .invoke(s"call $service $path")
  }
