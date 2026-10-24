package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.KindKits;
import java.util.Map;
import java.util.Optional;

/**
 * One view driven with no runtime or database: a change of its source goes to its handler with
 * the row the view holds for that subject, and the row it writes is kept where the test reads it.
 * The change crosses the source's serializer and the row the view's. Building the kit checks the
 * view's declared queries as a service's start does.
 */
public final class ViewTestKit<Src, Row> {

  private final KindKits.ViewKit kit;

  private ViewTestKit(KindKits.ViewKit kit) {
    this.kit = kit;
  }

  public static <Src, Row> ViewTestKit<Src, Row> of(Class<? extends View<Src, Row>> view) {
    return new ViewTestKit<>(KindKits.view(view));
  }

  /**
   * Hands {@code value}, a change of {@code subject}, to the view; the subject's sequence counts
   * from one.
   */
  public void change(String subject, Src value) {
    kit.change(subject, value, -1);
  }

  /** As {@link #change(String, Object)}, at the sequence number given. */
  public void change(String subject, Src value, long sequenceNumber) {
    kit.change(subject, value, sequenceNumber);
  }

  /** Tells the view that {@code subject} was deleted. */
  public void deleted(String subject) {
    kit.deleted(subject);
  }

  /** The row for {@code subject}, if the view holds one. */
  @SuppressWarnings("unchecked")
  public Optional<Row> row(String subject) {
    return (Optional<Row>) kit.row(subject);
  }

  /** Every row the view holds, by subject. */
  @SuppressWarnings("unchecked")
  public Map<String, Row> rows() {
    return (Map<String, Row>) kit.rows();
  }
}
