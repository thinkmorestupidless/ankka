package org.apache.pekko.http.impl.engine.ws

import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{AttributeKeys, HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.model.ws.{Message, WebSocketUpgrade}
import org.apache.pekko.http.scaladsl.settings.WebSocketSettings
import org.apache.pekko.stream.scaladsl.Flow

/**
 * Upgrades a request to a socket whose close frame says what ankka chose.
 *
 * pekko-http's public WebSocket API closes with 1000 when the handler's stream completes and with
 * 1011 "internal error" when it fails, and nothing else: there is no way to tell a client "going
 * away", "too large", "unread" or "not text". This file is the one place ankka reaches into
 * pekko-http's internal package to do so, which is why it lives in that package — `private[http]`
 * is visible from here and nowhere in ankka's own tree.
 *
 * It uses five internal names: `UpgradeToWebSocketLowLevel.handleFrames`, `WebSocket.stack`,
 * `FrameEvent.closeFrame`, `FrameStart` and `Protocol.Opcode.Close`. It upgrades with pekko's own
 * message stack — assembly, UTF-8 decoding, ping answers and the close handshake are all pekko's —
 * and changes one frame: the close frame ankka's side sends, when ankka chose a reason for it. A
 * close echoing the client's own is left as pekko wrote it.
 *
 * `SocketCloseCodeSuite` (testkit) asserts every code on the wire, so a pekko-http upgrade that
 * breaks this is a compile error or a red test. If the upgrade is ever not the low-level class,
 * this falls back to the public API, where a client is told 1000 or 1011 only — and that suite
 * fails then too, on purpose.
 */
object AnkkaSocketUpgrade:

  /**
   * The upgrade pekko-http attached to `request`, if it asked for one. Read from the attribute map
   * by key rather than through `attribute`, whose Java-to-Scala key mapping is an implicit Scala
   * 3.10 will stop finding.
   */
  def of(request: HttpRequest): Option[WebSocketUpgrade] =
    request.attributes.get(AttributeKeys.webSocketUpgrade).collect { case u: WebSocketUpgrade => u }

  /**
   * Whether `upgrade` lets the close frame be chosen; when not, `respond` sends 1000 or 1011 only.
   */
  def choosesCloseCodes(upgrade: WebSocketUpgrade): Boolean =
    upgrade.isInstanceOf[UpgradeToWebSocketLowLevel]

  /**
   * The 101 for `upgrade`, served by `messages`. `chosen` is read when ankka's side sends its close
   * frame, and names the code and reason that frame carries; `None` leaves pekko's.
   */
  def respond(
      upgrade: WebSocketUpgrade,
      messages: Flow[Message, Message, Any],
      subprotocol: Option[String],
      chosen: () => Option[(Int, String)],
      settings: WebSocketSettings,
      log: LoggingAdapter
  ): HttpResponse =
    upgrade match
      case low: UpgradeToWebSocketLowLevel =>
        val frames = WebSocket
          .stack(serverSide = true, settings, log = log)
          .join(messages)
          .map {
            case start: FrameStart if start.header.opcode == Protocol.Opcode.Close =>
              chosen() match
                case Some((code, reason)) => FrameEvent.closeFrame(code, reason)
                case None                 => start
            case other => other
          }
        low.handleFrames(frames, subprotocol)
      case other => other.handleMessages(messages, subprotocol)
