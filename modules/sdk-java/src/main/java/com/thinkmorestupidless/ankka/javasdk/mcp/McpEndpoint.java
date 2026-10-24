package com.thinkmorestupidless.ankka.javasdk.mcp;

import com.thinkmorestupidless.ankka.javasdk.bridge.McpCell;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Caller;
import com.thinkmorestupidless.ankka.javasdk.http.Principal;
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage;
import java.util.Optional;

/**
 * A service's tools, served to any MCP client at {@code path} ({@code /mcp} unless given). The
 * tools are declared in the constructor, each finished by a handler or by naming a command or query
 * the service already has:
 *
 * <pre>{@code
 * public class CartTools extends McpEndpoint {
 *   public CartTools() {
 *     tool("get_cart").describedAs("Reads a cart").query(ShoppingCart::getCart, "cartId");
 *     tool("add_item").describedAs("Adds an item").command(ShoppingCart::addItem, "cartId");
 *   }
 *   protected Acl acl() { return Acl.clientKeys(); }
 * }
 * }</pre>
 *
 * <p>Registered with {@code withMcpEndpoint}; the service then keeps client keys and, when {@link
 * #brokered} says so, signs people in at its own hostnames.
 */
public abstract class McpEndpoint {

  private final McpCell cell = McpCell.take();

  protected McpEndpoint() {
    this("/mcp");
  }

  protected McpEndpoint(String path) {
    cell.bind(path);
  }

  /** Who may reach the endpoint at all; a tool's own ACL is decided after it. */
  protected abstract Acl acl();

  /** The version the endpoint tells a client it is. */
  protected String version() {
    return "0.0.0";
  }

  /**
   * Whether the service signs people in itself, as an authorization server at its own hostnames,
   * sending them to the first issuer {@link #acl} admits tokens from.
   */
  protected boolean brokered() {
    return false;
  }

  /** Declares the tool {@code name}; finish it with a handler or a reference. */
  protected final ToolBuilder tool(String name) {
    return new ToolBuilder(cell, name, "", null);
  }

  /** Who made the call being handled. */
  protected final Caller caller() {
    return cell.caller();
  }

  /** The signed-in principal of the call being handled, when there is one. */
  protected final Optional<Principal> principal() {
    return cell.principal();
  }

  protected final ComponentClient componentClient() {
    return cell.componentClient();
  }

  protected final ServiceClients services() {
    return cell.services();
  }

  protected final SecretStore secrets() {
    return cell.secrets();
  }

  protected final ObjectStorage storage() {
    return cell.storage();
  }

  /** The service's client keys: issue, list and revoke them. */
  protected final ClientKeys clientKeys() {
    return cell.clientKeys();
  }
}
