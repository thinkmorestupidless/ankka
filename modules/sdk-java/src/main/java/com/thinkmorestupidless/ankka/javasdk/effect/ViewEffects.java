package com.thinkmorestupidless.ankka.javasdk.effect;

/** The effects a view's handlers return. */
public interface ViewEffects<Row> {

  /** Write the row for the change's source id. */
  ViewEffect<Row> updateRow(Row row);

  /** Remove the row for the change's source id. */
  ViewEffect<Row> deleteRow();

  /** Leave the row as it is. */
  ViewEffect<Row> ignore();
}
