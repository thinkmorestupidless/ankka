package com.thinkmorestupidless.ankka.javasdk.component;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Where a view's or a consumer's changes come from: a component of this service, or a topic. A
 * component source is resolved when the service starts, so naming a class the service does not
 * register fails the start.
 *
 * @param <Src> what each change carries
 */
public final class ChangeSource<Src> {

  /** What kind of source this is. */
  public enum Kind {
    /** An event sourced entity's events, or a key value entity's state. */
    ENTITY,
    /** A workflow's state. */
    WORKFLOW,
    /** A topic's messages. */
    TOPIC
  }

  private final Kind kind;
  private final @Nullable Class<?> component;
  private final @Nullable String topic;
  private final @Nullable String project;
  private final @Nullable Class<?> type;

  private ChangeSource(
      Kind kind,
      @Nullable Class<?> component,
      @Nullable String topic,
      @Nullable String project,
      @Nullable Class<?> type) {
    this.kind = kind;
    this.component = component;
    this.topic = topic;
    this.project = project;
    this.type = type;
  }

  /**
   * An entity of this service: an event sourced entity's events, or a key value entity's state.
   */
  public static <Src> ChangeSource<Src> of(Class<?> entity) {
    return new ChangeSource<>(Kind.ENTITY, Objects.requireNonNull(entity), null, null, null);
  }

  /** A workflow of this service: its state after each transition. */
  public static <Src> ChangeSource<Src> ofWorkflow(Class<?> workflow) {
    return new ChangeSource<>(Kind.WORKFLOW, Objects.requireNonNull(workflow), null, null, null);
  }

  /** A topic of this project, its messages decoded as {@code type}. */
  public static <Src> ChangeSource<Src> ofTopic(String topic, Class<Src> type) {
    return new ChangeSource<>(
        Kind.TOPIC, null, Objects.requireNonNull(topic), null, Objects.requireNonNull(type));
  }

  /** Another project's topic, as that project grants it, its messages decoded as {@code type}. */
  public static <Src> ChangeSource<Src> ofTopic(String project, String topic, Class<Src> type) {
    return new ChangeSource<>(
        Kind.TOPIC,
        null,
        Objects.requireNonNull(topic),
        Objects.requireNonNull(project),
        Objects.requireNonNull(type));
  }

  public Kind kind() {
    return kind;
  }

  public @Nullable Class<?> component() {
    return component;
  }

  public @Nullable String topic() {
    return topic;
  }

  public @Nullable String project() {
    return project;
  }

  public @Nullable Class<?> type() {
    return type;
  }

  @Override
  public String toString() {
    return switch (kind) {
      case ENTITY -> "entity(" + component.getSimpleName() + ")";
      case WORKFLOW -> "workflow(" + component.getSimpleName() + ")";
      case TOPIC -> "topic(" + (project == null ? "" : project + "/") + topic + ")";
    };
  }
}
