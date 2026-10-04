package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.SessionId
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.stream.scaladsl.Source
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Codecs

given JsonValueCodec[Vector[ApprovalRequest]] = Codecs.make[Vector[ApprovalRequest]]

/** Serves an agent's token stream as server-sent events. */
final class ChatEndpoint(client: ComponentClient) extends HttpEndpoint("/chat"):

  val acl: Acl = Acl.AllowAll

  /**
   * Streams a reply.
   *
   * The handler only *builds* the source; pekko-http pulls tokens as the client reads, so nothing
   * buffers the whole answer.
   */
  // docs:start sse
  sse("/{session}") { (session: String) =>
    client
      .forAgent(SessionId(session))
      .stream(WeatherAgent.chat)("What is the weather?")
  }
  // docs:end sse

  /**
   * Tokens that would be corrupted by naive SSE framing.
   *
   * A leading space is stripped by the protocol's own rules, and a raw newline terminates the data
   * field — splitting one token into two events.
   */
  sse("/awkward") { () =>
    Source(Vector("word", " leading", "trailing ", "with\nnewline", "  two  "))
  }

  /**
   * Streams a support agent's reply, ending with an event named `approval` when its turn stops to
   * wait for a supervisor.
   */
  // docs:start sse-approval
  sseEvents("/support/{session}") { (session: String) =>
    client
      .forAgent(SessionId(session))
      .streamParts(ApprovalAgent.chat)("refund order o-7")
      .map {
        case AgentPart.Text(text)                 => SseEvent.text(text)
        case AgentPart.AwaitingApproval(requests) => SseEvent.json("approval", requests)
      }
  }
  // docs:end sse-approval

  /** A plain source, to check the SSE plumbing independently of any model. */
  sse("/fixed/{session}") { (session: String) =>
    Source(Vector("one", "two", s"three-$session"))
  }
