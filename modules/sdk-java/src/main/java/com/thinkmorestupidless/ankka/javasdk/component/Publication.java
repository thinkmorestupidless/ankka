package com.thinkmorestupidless.ankka.javasdk.component;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The topic a consumer publishes to: this project's, or one another project grants. */
public final class Publication {

  private final String topic;
  private final @Nullable String project;

  private Publication(String topic, @Nullable String project) {
    this.topic = Objects.requireNonNull(topic);
    this.project = project;
  }

  public static Publication to(String topic) {
    return new Publication(topic, null);
  }

  public static Publication to(String project, String topic) {
    return new Publication(topic, Objects.requireNonNull(project));
  }

  public String topic() {
    return topic;
  }

  public Optional<String> project() {
    return Optional.ofNullable(project);
  }

  @Override
  public String toString() {
    return project == null ? topic : project + "/" + topic;
  }
}
