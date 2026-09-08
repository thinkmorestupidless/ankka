package nakka.testkit

import nakka.agent.*
import nakka.core.SessionId
import nakka.http.*
import nakka.sdk.ComponentClient
import org.apache.pekko.stream.scaladsl.Source

/** Serves an agent's token stream as server-sent events. */
final class ChatEndpoint(client: ComponentClient) extends HttpEndpoint("/chat"):

  val acl: Acl = Acl.AllowAll

  /**
   * Streams a reply.
   *
   * The handler only *builds* the source; pekko-http pulls tokens as the client reads,
   * so nothing buffers the whole answer.
   */
  sse("/{session}") { (session: String) =>
    client
      .forAgent(SessionId(session))
      .stream(WeatherAgent.chat)("What is the weather?")
  }

  /**
   * Tokens that would be corrupted by naive SSE framing.
   *
   * A leading space is stripped by the protocol's own rules, and a raw newline
   * terminates the data field — splitting one token into two events.
   */
  sse("/awkward") { () =>
    Source(Vector("word", " leading", "trailing ", "with\nnewline", "  two  "))
  }

  /** A plain source, to check the SSE plumbing independently of any model. */
  sse("/fixed/{session}") { (session: String) =>
    Source(Vector("one", "two", s"three-$session"))
  }
