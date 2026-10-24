package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.bridge.GraphDeltas;
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One graph delta under the contract {@code ankka.graph-delta.v1}, as a graph consumer publishes it
 * and the merge sink reads it: a node or an edge as it now is, whole, or a tombstone marking one
 * deleted, at a version.
 *
 * <p>{@code kind} is {@code node}, {@code edge} or {@code tombstone}; {@code element} is which id
 * space it is about, {@code node} or {@code edge}. An edge, and an edge's tombstone, carry its
 * type and both ends. Property values are what the sink stores: every integer a {@code Long}, a
 * float with a whole value that {@code Long}, a list a {@code List} of one kind. A value is read
 * only by {@link #read(byte[])}, so one in hand is one the sink accepts.
 */
public record GraphDelta(
    String kind,
    String element,
    String id,
    long version,
    List<String> labels,
    Optional<String> edgeType,
    Optional<String> from,
    Optional<String> to,
    Map<String, Object> properties) {

  /** The contract's name: a delta's manifest and the {@code ce-type} of its record. */
  public static final String SCHEMA_NAME = "ankka.graph-delta.v1";

  /** The record key every delta for this element is published under. */
  public String key() {
    return element + ":" + id;
  }

  public boolean isTombstone() {
    return kind.equals("tombstone");
  }

  /** The record key of a node's deltas: {@code node:<id>}. */
  public static String nodeKey(String id) {
    return "node:" + id;
  }

  /** The record key of an edge's deltas: {@code edge:<id>}. */
  public static String edgeKey(String id) {
    return "edge:" + id;
  }

  /**
   * A delta read from a record's value, as the sink reads it.
   *
   * @throws IllegalArgumentException saying why, for a value the sink refuses
   */
  public static GraphDelta read(byte[] value) {
    return GraphDeltas.read(value);
  }

  /**
   * A delta read from a record's key and value; a key that is not the delta's element key is
   * refused, as the sink refuses it. A {@code null} key is a record with none, also refused.
   */
  public static GraphDelta read(String key, byte[] value) {
    return GraphDeltas.read(key, value);
  }

  /** The delta's serializer, under the contract's name. */
  public static Serializer<GraphDelta> serializer() {
    return GraphDeltas.serializer();
  }
}
