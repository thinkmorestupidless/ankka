package com.thinkmorestupidless.ankka.javasdk.http;

/**
 * Which workload sent a request, as the platform's mutual TLS established it: the gateway (the
 * internet), another service, a registered machine, or this machine when nothing is deployed.
 */
public sealed interface Caller {

  record Gateway() implements Caller {}

  record Service(String project, String name) implements Caller {}

  record Machine(String organization, String name) implements Caller {}

  record Local() implements Caller {}
}
