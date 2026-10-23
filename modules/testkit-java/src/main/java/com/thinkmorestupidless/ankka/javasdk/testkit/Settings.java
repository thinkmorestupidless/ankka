package com.thinkmorestupidless.ankka.javasdk.testkit;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * How {@link AnkkaTestKit} starts a service: configuration above everything else, stand-ins, where
 * other services are, and what stands behind its storage client.
 */
public final class Settings {

  private final Map<String, String> config;
  private final List<String> services;
  private final Map<String, URI> local;
  private final boolean storageFromEnvironment;

  private Settings(
      Map<String, String> config,
      List<String> services,
      Map<String, URI> local,
      boolean storageFromEnvironment) {
    this.config = Map.copyOf(config);
    this.services = List.copyOf(services);
    this.local = Map.copyOf(local);
    this.storageFromEnvironment = storageFromEnvironment;
  }

  public static Settings defaults() {
    return new Settings(Map.of(), List.of(), Map.of(), false);
  }

  /** A configuration value the service is started with, on every restart too. */
  public Settings withConfig(String path, String value) {
    Map<String, String> more = new LinkedHashMap<>(config);
    more.put(path, value);
    return new Settings(more, services, local, storageFromEnvironment);
  }

  /**
   * A stand-in for another service the service calls by {@code name}, answered as the test says
   * through {@link AnkkaTestKit#service(String)}.
   */
  public Settings withScriptedService(String name) {
    return new Settings(
        config,
        Stream.concat(services.stream(), Stream.of(name)).toList(),
        local,
        storageFromEnvironment);
  }

  /**
   * Another service, running at {@code address}, that the service calls by {@code name}: another
   * kit's {@link AnkkaTestKit#http()}, say, as {@code ankka.local-services.<name>} gives it.
   */
  public Settings withLocalService(String name, URI address) {
    Map<String, URI> more = new LinkedHashMap<>(local);
    more.put(name, address);
    return new Settings(config, services, more, storageFromEnvironment);
  }

  /**
   * The storage client the runtime chooses for itself, rather than a store in memory of the kit's
   * own: on a developer's machine with no variable of an object store, a directory store (under
   * {@code ankka.storage.directory} when the configuration names one).
   */
  public Settings withStorageFromEnvironment() {
    return new Settings(config, services, local, true);
  }

  public Map<String, String> config() {
    return config;
  }

  public List<String> scriptedServices() {
    return services;
  }

  public Map<String, URI> localServices() {
    return local;
  }

  public boolean storageFromEnvironment() {
    return storageFromEnvironment;
  }
}
