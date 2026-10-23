package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import java.util.Map;

/** The shape of a value a blueprint passes: a small JSON schema, checked at each step. */
public interface Shape {

  static Shape any() {
    return AgentBridge.shape("any");
  }

  static Shape string() {
    return AgentBridge.shape("string");
  }

  static Shape number() {
    return AgentBridge.shape("number");
  }

  static Shape integer() {
    return AgentBridge.shape("integer");
  }

  static Shape bool() {
    return AgentBridge.shape("boolean");
  }

  static Shape arr(Shape items) {
    return AgentBridge.arr(items);
  }

  /** An object with these fields, every one required. */
  static Shape obj(Map<String, Shape> fields) {
    return AgentBridge.obj(fields);
  }

  /** One of these strings. */
  static Shape enumOf(String... values) {
    return AgentBridge.enumOf(values);
  }

  /** A shape from its JSON schema; refused, naming why, when it is not one. */
  static Shape fromJson(String schema) {
    return AgentBridge.shapeFromJson(schema);
  }

  /** The JSON schema. */
  String json();
}
