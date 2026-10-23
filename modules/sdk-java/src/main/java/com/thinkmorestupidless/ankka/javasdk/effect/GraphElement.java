package com.thinkmorestupidless.ankka.javasdk.effect;

/** A node, an edge or a tombstone of either, built by {@code graph()}. */
public interface GraphElement {

  /** The same element stated at {@code version} rather than at the change's sequence number. */
  GraphElement at(long version);

  /** {@code node} or {@code edge}. */
  String kind();

  String id();

  /** The record key its delta is published under. */
  String key();
}
