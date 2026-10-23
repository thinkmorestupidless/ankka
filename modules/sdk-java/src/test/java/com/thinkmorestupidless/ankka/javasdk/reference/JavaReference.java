package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.conformance.ConformanceEntry;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The reference service in Java, as the conformance suite hosts it: every component and route the
 * Scala reference has, but its agents, which the Java SDK's agents are to add.
 */
public final class JavaReference implements ConformanceEntry {

  private static volatile Acl authenticated = Acl.denyAll();

  /** What the suite's verified tokens are admitted by; nobody until the suite says. */
  static Acl authenticated() {
    return authenticated;
  }

  private static final List<Class<?>> COMPONENTS =
      List.of(
          ShoppingCartEntity.class,
          ConformanceEntity.class,
          Profile.class,
          Checkout.class,
          CheckoutRows.class,
          CheckoutEnds.class,
          CartRows.class,
          CheckoutRecorder.class,
          CheckoutFanout.class,
          TopicRows.class,
          TopicRelay.class,
          ContractRelay.class,
          PartnerRelay.class,
          TreeNode.class,
          TreeRows.class,
          ReplicatedVehicle.class,
          VehicleFeed.class,
          JoinedLeft.class,
          JoinedRight.class,
          JoinedRows.class,
          CartGraph.class,
          ProfileGraph.class,
          Reminder.class);

  @Override
  public String language() {
    return "Java";
  }

  @Override
  public List<Class<?>> components() {
    return COMPONENTS;
  }

  @Override
  public Set<String> componentIds() {
    return COMPONENTS.stream()
        .map(
            c ->
                c.getAnnotation(
                        com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId.class)
                    .value())
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Map<String, Set<String>> readOnlyHandlers() {
    return Map.of(
        "shopping-cart", Set.of("get-cart", "total-quantity"),
        "conformance", Set.of("count"),
        "profile", Set.of("get"),
        "checkout", Set.of("status"),
        "replicated-vehicle", Set.of("get"));
  }

  @Override
  public List<Class<? extends HttpEndpoint>> endpoints() {
    return List.of(
        CartsEndpoint.class,
        TreeEndpoint.class,
        VehiclesEndpoint.class,
        JoinedEndpoint.class,
        ConformanceEndpoint.class,
        ClosedEndpoint.class,
        PrivateEndpoint.class,
        CallersEndpoint.class,
        SelfCallersEndpoint.class,
        GrantedCallersEndpoint.class,
        McpKeysEndpoint.class);
  }

  @Override
  public List<Class<? extends McpEndpoint>> mcpEndpoints() {
    return List.of(CartMcp.class);
  }

  @Override
  public void authenticateWith(Acl acl) {
    authenticated = acl;
  }
}
