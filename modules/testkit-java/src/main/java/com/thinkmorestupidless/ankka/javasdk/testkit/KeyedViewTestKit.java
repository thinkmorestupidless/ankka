package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery;
import com.thinkmorestupidless.ankka.javasdk.component.KeyedView;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.KindKits;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * One keyed view driven with no runtime or database: a change of one of its sources goes to that
 * source's handler, and the rows it names are kept where the test reads them.
 *
 * <p>A declared query is SQL, which nothing here runs, so the test says what each query a handler
 * asks answers ({@link #answering}); a query nobody answered fails the change, naming it.
 */
public final class KeyedViewTestKit<Row> {

  private final KindKits.KeyedViewKit kit;

  private KeyedViewTestKit(KindKits.KeyedViewKit kit) {
    this.kit = kit;
  }

  public static <Row> KeyedViewTestKit<Row> of(Class<? extends KeyedView<Row>> view) {
    return new KeyedViewTestKit<>(KindKits.keyedView(view));
  }

  /** What {@code query} answers with, given the values it is asked with. */
  public KeyedViewTestKit<Row> answering(
      ViewQuery query, Function<Map<String, String>, List<Row>> answer) {
    kit.answering(query, answer);
    return this;
  }

  /** Hands {@code value}, a change of {@code subject} of the component {@code source}. */
  public void change(Class<?> source, String subject, Object value) {
    kit.change(KindKits.sourceName(source), subject, value);
  }

  /** Hands {@code value}, a message about {@code subject} on {@code topic}. */
  public void change(String topic, String subject, Object value) {
    kit.change(topic, subject, value);
  }

  /** Tells the handler of {@code source}'s deletions that {@code subject} was deleted. */
  public void deleted(Class<?> source, String subject) {
    kit.deleted(KindKits.sourceName(source), subject);
  }

  /** The row under {@code key}, if the view holds one. */
  @SuppressWarnings("unchecked")
  public Optional<Row> row(String key) {
    return (Optional<Row>) kit.row(key);
  }

  /** Every row the view holds, by key. */
  @SuppressWarnings("unchecked")
  public Map<String, Row> rows() {
    return (Map<String, Row>) kit.rows();
  }
}
