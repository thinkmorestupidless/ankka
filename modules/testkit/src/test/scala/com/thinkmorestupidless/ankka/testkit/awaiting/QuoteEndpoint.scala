package com.thinkmorestupidless.ankka.testkit.awaiting

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.*

import scala.concurrent.duration.*

/** The routes of a pricing service that answer a quote's request with the quote. */
final class QuoteEndpoint(clients: EndpointClients) extends HttpEndpoint("/quotes"):

  private given JsonValueCodec[Quote]        = Codecs.make[Quote]
  private given JsonValueCodec[QuoteRequest] = Codecs.make[QuoteRequest]

  val acl: Acl = Acl.AllowAll

  private val client = clients.componentClient

  // docs:start start-and-await
  /** Starts the quote and answers the request with the quote, once the workflow has ended. */
  postBody("/{id}") { (id: String, request: QuoteRequest) =>
    client
      .forWorkflow(EntityId(id))
      .call(QuoteWorkflow.start)
      .thenAwaitEnd(30.seconds)
      .invoke(request)
  }
  // docs:end start-and-await

  // docs:start await-later
  /**
   * Answers with a quote started earlier, by anyone: at once if it has ended, when it ends if not.
   */
  get("/{id}/wait") { (id: String) =>
    client.forWorkflow(EntityId(id)).awaitEnd(QuoteWorkflow, 10.seconds)
  }
  // docs:end await-later

  // docs:start sse-await
  /**
   * A quote's end as server-sent events: a heartbeat while it is worked out, then the quote, on one
   * connection however long that takes.
   */
  sseEvents("/{id}/events") { (id: String) =>
    clients.awaitEnd(EntityId(id), QuoteWorkflow, 10.minutes)
  }
  // docs:end sse-await

  // docs:start whole-await
  /** A wait answered as one whole response, which the service's idle timeout cuts if it is long. */
  get("/{id}/whole") { (id: String) =>
    client.forWorkflow(EntityId(id)).awaitEnd(QuoteWorkflow, 10.minutes)
  }
  // docs:end whole-await
