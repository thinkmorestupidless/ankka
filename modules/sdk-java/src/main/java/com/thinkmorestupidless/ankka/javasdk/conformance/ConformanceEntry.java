package com.thinkmorestupidless.ankka.javasdk.conformance;

import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A reference service written through the Java SDK, as ankka's conformance suite hosts it: the
 * classes a service would register, named here so the suite can host them beside its own harness.
 * Implemented by the Java and Kotlin references, with a public constructor taking nothing. Not for
 * services: a service is started with {@link com.thinkmorestupidless.ankka.javasdk.Ankka}.
 */
public interface ConformanceEntry {

  /** The language the reference is written in, as the suite's report names it. */
  String language();

  /** The component classes, as {@code register} would be given them. */
  List<Class<?>> components();

  /** The component ids the components declare, which the suite holds discovery to. */
  Set<String> componentIds();

  /** The handlers declared read only, by component id. */
  Map<String, Set<String>> readOnlyHandlers();

  /** The HTTP endpoints, apart from the MCP ones. */
  List<Class<? extends HttpEndpoint>> endpoints();

  /** The MCP endpoints. */
  List<Class<? extends McpEndpoint>> mcpEndpoints();

  /**
   * The suite's ACL for a verified token from its own test issuer, handed over before any endpoint
   * is built: the reference's authenticated routes admit what it admits.
   */
  void authenticateWith(Acl authenticated);
}
