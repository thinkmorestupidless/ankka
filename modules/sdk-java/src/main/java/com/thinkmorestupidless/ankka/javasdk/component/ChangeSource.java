package com.thinkmorestupidless.ankka.javasdk.component;

import java.time.Instant;
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

  /**
   * Where a topic source begins the first time its consumer group reads a partition; after that the
   * group resumes where it got to.
   */
  public enum Start {
    /** The oldest message the broker still holds. */
    EARLIEST,
    /** Only what is published from now on. */
    LATEST,
    /** The first message published at or after {@link #startAt()}. */
    AT
  }

  private final Kind kind;
  private final @Nullable Class<?> component;
  private final @Nullable String topic;
  private final @Nullable String project;
  private final @Nullable Class<?> type;
  private final @Nullable Start start;
  private final @Nullable Instant startAt;

  private ChangeSource(
      Kind kind,
      @Nullable Class<?> component,
      @Nullable String topic,
      @Nullable String project,
      @Nullable Class<?> type) {
    this(kind, component, topic, project, type, null, null);
  }

  private ChangeSource(
      Kind kind,
      @Nullable Class<?> component,
      @Nullable String topic,
      @Nullable String project,
      @Nullable Class<?> type,
      @Nullable Start start,
      @Nullable Instant startAt) {
    this.kind = kind;
    this.component = component;
    this.topic = topic;
    this.project = project;
    this.type = type;
    this.start = start;
    this.startAt = startAt;
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

  /**
   * This topic source, read from the oldest message the broker holds. A topic source must say where
   * it starts, or the service does not start.
   */
  public ChangeSource<Src> fromEarliest() {
    return starting(Start.EARLIEST, null);
  }

  /** This topic source, read from what is published after the consumer first reads it. */
  public ChangeSource<Src> fromLatest() {
    return starting(Start.LATEST, null);
  }

  /** This topic source, read from the first message published at or after {@code time}. */
  public ChangeSource<Src> from(Instant time) {
    return starting(Start.AT, Objects.requireNonNull(time));
  }

  private ChangeSource<Src> starting(Start start, @Nullable Instant at) {
    if (kind != Kind.TOPIC)
      throw new IllegalStateException("only a topic source has a start position: " + this);
    return new ChangeSource<>(kind, component, topic, project, type, start, at);
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

  public @Nullable Start start() {
    return start;
  }

  public @Nullable Instant startAt() {
    return startAt;
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
