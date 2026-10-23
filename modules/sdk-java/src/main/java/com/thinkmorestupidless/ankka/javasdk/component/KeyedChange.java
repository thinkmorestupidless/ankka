package com.thinkmorestupidless.ankka.javasdk.component;

/** The change a keyed view's source method is handling, and the view's rows as they stand. */
public interface KeyedChange<Row> {

  /** The source id: the entity's or workflow's id, or the message's subject. */
  String subject();

  /** The change's position in its source. */
  long sequenceNumber();

  /** The view's rows, read before this change's effect is written. */
  ViewRows<Row> rows();
}
