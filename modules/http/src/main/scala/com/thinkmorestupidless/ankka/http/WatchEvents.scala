package com.thinkmorestupidless.ankka.http

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import com.thinkmorestupidless.ankka.sdk.{WatchEnded, WatchEvent}
import org.apache.pekko.stream.scaladsl.Source

extension [Row, M](watch: Source[WatchEvent[Row], M])
  /**
   * A watch of a view as server-sent events, for an `sseEvents` route: each row as an event named
   * `row` carrying `{"key": …, "row": …}`, each removal as `removed` carrying `{"key": …}`, the
   * caught-up marker as `caught-up` carrying `{}`, and a watch that ended with a reason as a last
   * event `ended` carrying `{"reason": …}` — `rebuilt`, `instance-stopping`, `listener-lost` or
   * `unread` — so a page is told why, and reconnects to be given the rows now.
   *
   * {{{
   * import com.thinkmorestupidless.ankka.http.asSse
   * sseEvents("/carts/open")(() => views.forView(CartRows).watch(CartRows.openCarts).asSse)
   * }}}
   */
  def asSse(using codec: JsonValueCodec[Row]): Source[SseEvent, M] =
    watch
      .map {
        case WatchEvent.Row(key, row) =>
          SseEvent.named("row", s"""{"key":${JsonText.encode(key)},"row":${writeToString(row)}}""")
        case WatchEvent.Removed(key) =>
          SseEvent.named("removed", s"""{"key":${JsonText.encode(key)}}""")
        case WatchEvent.CaughtUp => SseEvent.named("caught-up", "{}")
      }
      .recover { case WatchEnded(reason) =>
        SseEvent.named("ended", s"""{"reason":${JsonText.encode(reason.wire)}}""")
      }
