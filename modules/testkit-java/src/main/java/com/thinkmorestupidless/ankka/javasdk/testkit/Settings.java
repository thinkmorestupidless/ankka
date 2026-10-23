package com.thinkmorestupidless.ankka.javasdk.testkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** How {@link AnkkaTestKit} starts a service: configuration above everything else, and stand-ins. */
public final class Settings {

  private final Map<String, String> config;
  private final List<String> services;

  private Settings(Map<String, String> config, List<String> services) {
    this.config = Map.copyOf(config);
    this.services = List.copyOf(services);
  }

  public static Settings defaults() {
    return new Settings(Map.of(), List.of());
  }

  /** A configuration value the service is started with, on every restart too. */
  public Settings withConfig(String path, String value) {
    Map<String, String> more = new LinkedHashMap<>(config);
    more.put(path, value);
    return new Settings(more, services);
  }

  /**
   * A stand-in for another service the service calls by {@code name}, answered as the test says
   * through {@link AnkkaTestKit#service(String)}.
   */
  public Settings withScriptedService(String name) {
    return new Settings(config, Stream.concat(services.stream(), Stream.of(name)).toList());
  }

  public Map<String, String> config() {
    return config;
  }

  public List<String> scriptedServices() {
    return services;
  }
}
