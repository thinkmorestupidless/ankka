package com.thinkmorestupidless.ankka.javasdk.client;

/**
 * Calls to the components of this service. A call names its handler by a method reference, so the
 * compiler checks the input and the reply:
 *
 * <pre>{@code
 * componentClient.forEventSourcedEntity("cart-1").call(ShoppingCart::addItem).invoke(item);
 * }</pre>
 */
public interface ComponentClient {

  EntityRef forEventSourcedEntity(String entityId);

  EntityRef forKeyValueEntity(String entityId);

  WorkflowRef forWorkflow(String workflowId);
}
