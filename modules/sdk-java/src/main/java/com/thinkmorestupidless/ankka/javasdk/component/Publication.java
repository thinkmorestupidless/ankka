package com.thinkmorestupidless.ankka.javasdk.component;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The topic a consumer publishes to: this project's, or one another project grants. */
public final class Publication {

  private final String topic;
  private final @Nullable String project;
  private final @Nullable Contract contract;
  private final @Nullable String broker;

  private Publication(
      String topic, @Nullable String project, @Nullable Contract contract, @Nullable String broker) {
    this.topic = Objects.requireNonNull(topic);
    this.project = project;
    this.contract = contract;
    this.broker = broker;
  }

  public static Publication to(String topic) {
    return new Publication(topic, null, null, null);
  }

  public static Publication to(String project, String topic) {
    return new Publication(topic, Objects.requireNonNull(project), null, null);
  }

  /** This publication, its messages keeping {@code contract}. */
  public Publication withContract(Contract contract) {
    return new Publication(topic, project, Objects.requireNonNull(contract), broker);
  }

  /** This publication, to the broker the service declares as {@code broker}. */
  public Publication onBroker(String broker) {
    return new Publication(topic, project, contract, Objects.requireNonNull(broker));
  }

  public Optional<Contract> contract() {
    return Optional.ofNullable(contract);
  }

  public Optional<String> broker() {
    return Optional.ofNullable(broker);
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
