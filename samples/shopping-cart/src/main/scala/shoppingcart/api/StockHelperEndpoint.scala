package shoppingcart.api

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.SessionId
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import shoppingcart.application.StockHelper

import java.util.UUID

/**
 * Asks [[StockHelper]] a question: `GET /helper/ask?question=...` answers how many times its tool
 * has run in this instance, on the first line, and the agent's reply after it. Each request is a
 * session of its own.
 */
final class StockHelperEndpoint(client: ComponentClient) extends HttpEndpoint("/helper"):

  val acl: Acl = Acl.AllowAll

  get("/ask") { () =>
    val question = query.optional[String]("question").getOrElse("Are apples in stock?")
    val reply = client
      .forAgent(SessionId(UUID.randomUUID().toString))
      .call(StockHelper.ask)
      .invoke(question)
    s"lookups=${StockHelper.lookups.get}\n$reply"
  }
