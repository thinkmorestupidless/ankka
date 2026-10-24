package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.InstallationsKit;
import java.util.List;

/**
 * A group of installations of one service, each on a database of its own, replicating its
 * replicated entities through the runtime's own transport. Made by {@link
 * AnkkaTestKit#installations(String...)}; what reaches each installation can be paused and
 * blocked, so a test can hold one apart from the others.
 *
 * <pre>{@code
 * try (var group = AnkkaTestKit.installations("leipzig", "bratislava")) {
 *   var leipzig = group.start("leipzig", Main.service());
 *   var bratislava = group.start("bratislava", Main.service());
 *   ...
 * }
 * }</pre>
 *
 * <p>Several kits in one JVM are not installations on a network: this proves the runtime's
 * replication, not a transport between machines.
 */
public final class Installations implements AutoCloseable {

  private final InstallationsKit group;

  Installations(InstallationsKit group) {
    this.group = group;
  }

  /** The group's installations, in the order they were named. */
  public List<String> names() {
    return group.names();
  }

  /** Starts the installation {@code name}, ready before it is returned. */
  public AnkkaTestKit start(String name, ServiceBuilder service) {
    return start(name, service, Settings.defaults());
  }

  /** {@link #start(String, ServiceBuilder)} with settings; stand-in services are not offered. */
  public AnkkaTestKit start(String name, ServiceBuilder service, Settings settings) {
    return new AnkkaTestKit(group.start(name, service, settings));
  }

  /**
   * Stops the installation {@code name} and starts it again on the same database, with the
   * components {@code service} registers: an installation redeployed.
   */
  public AnkkaTestKit restart(String name, ServiceBuilder service) {
    return new AnkkaTestKit(group.restart(name, service));
  }

  /** The started installation {@code name}. */
  public AnkkaTestKit get(String name) {
    return new AnkkaTestKit(group.get(name));
  }

  /** Stalls every record on its way to {@code installation} until it is resumed. */
  public void pause(String installation) {
    group.pause(installation);
  }

  /** Lets what was stalled on its way to {@code installation} through, in order. */
  public void resume(String installation) {
    group.resume(installation);
  }

  /** Makes {@code to} unreachable from {@code from} until it is unblocked. */
  public void block(String from, String to) {
    group.block(from, to);
  }

  public void unblock(String from, String to) {
    group.unblock(from, to);
  }

  /** Stops every installation the group started. */
  @Override
  public void close() {
    group.close();
  }
}
