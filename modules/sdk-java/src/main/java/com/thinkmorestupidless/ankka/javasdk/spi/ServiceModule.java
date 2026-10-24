package com.thinkmorestupidless.ankka.javasdk.spi;

/**
 * Component kinds from a library beside the SDK, handed to a service by {@link
 * com.thinkmorestupidless.ankka.javasdk.ServiceBuilder#withModule}: the agent library's {@code
 * Agents.module()}. A registered class the module recognises is read by the module, at start, with
 * every other class; nothing on the classpath is looked for. A service that registers such a class
 * without its module refuses to start, naming the module.
 *
 * <p>Only the libraries the SDK publishes implement it.
 */
public interface ServiceModule {}
