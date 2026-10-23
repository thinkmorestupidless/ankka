package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import java.util.concurrent.Flow;

/**
 * A watch of a view's rows. It is read once, either as events or as server-sent events; nothing
 * is queried until it is subscribed to.
 */
public interface ViewWatch<Row> {

  /**
   * The rows now, {@link WatchEvent.CaughtUp}, then each write and removal; a watch the platform
   * ends (the view rebuilt, the instance stopping) ends with {@link WatchEvent.Ended} saying why.
   */
  Flow.Publisher<WatchEvent<Row>> events();

  /**
   * The same, for an {@code @Sse} route: each row as an event named {@code row} carrying {@code
   * {"key": …, "row": …}}, each removal as {@code removed} carrying {@code {"key": …}}, the
   * caught-up marker as {@code caught-up} carrying {@code {}}, and an end as {@code ended} carrying
   * {@code {"reason": …}}, so a page knows to reconnect.
   */
  Flow.Publisher<SseEvent> asSse();
}
