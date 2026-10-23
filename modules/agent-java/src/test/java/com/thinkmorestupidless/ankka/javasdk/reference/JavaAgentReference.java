package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider;
import com.thinkmorestupidless.ankka.agent.javasdk.conformance.AgentConformanceEntry;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The whole reference service in Java, as the conformance suite hosts it: {@link JavaReference}'s
 * components and routes, and its agents beside them, which need the agent module.
 */
public final class JavaAgentReference implements AgentConformanceEntry {

  private static volatile ModelProvider second;

  /** The model the suite configured under the name {@code second}. */
  static ModelProvider second() {
    var model = second;
    if (model == null) throw new IllegalStateException("the suite named no second model");
    return model;
  }

  private static final List<Class<?>> AGENTS =
      List.of(Assistant.class, Approver.class, Answerer.class);

  private final JavaReference base = new JavaReference();

  @Override
  public String language() {
    return base.language();
  }

  @Override
  public List<Class<?>> components() {
    var all = new ArrayList<>(base.components());
    all.addAll(AGENTS);
    return List.copyOf(all);
  }

  @Override
  public Set<String> componentIds() {
    return components().stream()
        .map(c -> c.getAnnotation(ComponentId.class).value())
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Map<String, Set<String>> readOnlyHandlers() {
    return base.readOnlyHandlers();
  }

  @Override
  public List<Class<? extends HttpEndpoint>> endpoints() {
    var all = new ArrayList<>(base.endpoints());
    all.add(AgentsEndpoint.class);
    all.add(AutonomousEndpoint.class);
    return List.copyOf(all);
  }

  @Override
  public List<Class<? extends McpEndpoint>> mcpEndpoints() {
    return base.mcpEndpoints();
  }

  @Override
  public void authenticateWith(Acl acl) {
    base.authenticateWith(acl);
  }

  @Override
  public void secondModel(ModelProvider model) {
    second = model;
  }
}
