package com.thinkmorestupidless.ankka.javasdk.effect;

import java.util.List;
import java.util.Map;

/**
 * Builds graph elements. A property's value is a {@code String}, {@code Boolean}, {@code Integer},
 * {@code Long} or {@code Double}, or a non-empty {@code List} of one of those; anything else, or an
 * element the graph rules refuse, is an {@code IllegalArgumentException} where it is built.
 */
public interface GraphElements {

  GraphElement node(String id, List<String> labels, Map<String, Object> properties);

  GraphElement edge(
      String id, String type, String from, String to, Map<String, Object> properties);

  GraphElement tombstoneNode(String id);

  GraphElement tombstoneEdge(String id, String type, String from, String to);
}
