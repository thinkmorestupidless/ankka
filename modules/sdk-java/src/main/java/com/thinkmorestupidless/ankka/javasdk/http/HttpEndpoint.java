package com.thinkmorestupidless.ankka.javasdk.http;

import com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeys;
import com.thinkmorestupidless.ankka.javasdk.bridge.EndpointCell;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients;
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage;
import com.thinkmorestupidless.ankka.javasdk.client.ViewClient;
import java.util.Optional;

/**
 * Routes over HTTP. Each route is a method annotated with its verb and path template; its
 * parameters are bound by {@code @Path}, {@code @QueryParam}, {@code @Header} and {@code @Body}, or
 * by name when a parameter is named as a template segment is.
 *
 * <pre>{@code
 * public class CartEndpoint extends HttpEndpoint {
 *   @Override
 *   protected Acl acl() { return Acl.allowAll(); }
 *
 *   @Post("/carts/{id}/items")
 *   public Done addItem(String id, @Body LineItem item) {
 *     return componentClient().forEventSourcedEntity(id).call(ShoppingCart::addItem).invoke(item);
 *   }
 * }
 * }</pre>
 *
 * A constructor takes nothing. One instance serves every request.
 */
public abstract class HttpEndpoint {

  private final EndpointCell cell = EndpointCell.take();

  protected HttpEndpoint() {}

  /** Who may call this endpoint's routes. */
  protected abstract Acl acl();

  /** The request being handled, on the handler's own thread. */
  protected final Request request() {
    return cell.request();
  }

  /** Which workload sent the request. */
  protected final Caller caller() {
    return cell.request().caller();
  }

  /** Who the endpoint's ACL authenticated, if it authenticates. */
  protected final Optional<Principal> principal() {
    return cell.request().principal();
  }

  /** Calls to this service's components. */
  protected final ComponentClient componentClient() {
    return cell.componentClient();
  }

  /** Queries of this service's views. */
  protected final ViewClient viewClient() {
    return cell.viewClient();
  }

  /** Calls to other services, by name. */
  protected final ServiceClients services() {
    return cell.services();
  }

  /** The service's client keys, for the callers of its MCP endpoint. */
  protected final ClientKeys clientKeys() {
    return cell.clientKeys();
  }

  /** The service's own secrets. */
  protected final SecretStore secrets() {
    return cell.secrets();
  }

  /** The service's buckets. */
  protected final ObjectStorage storage() {
    return cell.storage();
  }
}
