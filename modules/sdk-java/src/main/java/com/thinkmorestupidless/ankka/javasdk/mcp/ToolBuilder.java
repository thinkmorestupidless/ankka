package com.thinkmorestupidless.ankka.javasdk.mcp;

import com.thinkmorestupidless.ankka.javasdk.bridge.McpCell;
import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One tool being declared. A handler's answer is its text: a {@code String} as it is, {@code Done}
 * as {@code done}, anything else as its JSON. A {@code CommandException} or {@code
 * ProblemException} it throws is a tool error with the problem's detail.
 */
public final class ToolBuilder {

  private final McpCell cell;
  private final String name;
  private final String description;
  private final @Nullable Acl acl;

  ToolBuilder(McpCell cell, String name, String description, @Nullable Acl acl) {
    this.cell = cell;
    this.name = name;
    this.description = description;
    this.acl = acl;
  }

  /** What the tool does: all a client's model has to decide whether to call it. */
  public ToolBuilder describedAs(String description) {
    return new ToolBuilder(cell, name, description, acl);
  }

  /** The tool answers to {@code acl} after the endpoint's. */
  public ToolBuilder withAcl(Acl acl) {
    return new ToolBuilder(cell, name, description, acl);
  }

  /** A tool taking no arguments. */
  public McpTool handle(Supplier<?> handler) {
    return cell.handle(name, description, acl, handler);
  }

  /** The tool's arguments as one record, its schema read from the record's components. */
  public <A> Arguments<A> arguments(Class<A> type) {
    return new Arguments<>(this, type);
  }

  /** A command of an entity or workflow, its id from the argument {@code id}, its input beside. */
  public <C, I, O> McpTool command(CommandRef<C, I, O> handler, String id) {
    return cell.reference(name, description, acl, handler, id, null, false);
  }

  /** A command whose input is not a record, under the argument {@code input}. */
  public <C, I, O> McpTool command(CommandRef<C, I, O> handler, String id, String input) {
    return cell.reference(name, description, acl, handler, id, input, false);
  }

  /** A command that takes nothing but the id. */
  public <C, O> McpTool command(NoArgRef<C, O> handler, String id) {
    return cell.reference(name, description, acl, handler, id, null, false);
  }

  /** A query: as {@code command}, for a handler that only reads. */
  public <C, I, O> McpTool query(CommandRef<C, I, O> handler, String id) {
    return cell.reference(name, description, acl, handler, id, null, true);
  }

  public <C, I, O> McpTool query(CommandRef<C, I, O> handler, String id, String input) {
    return cell.reference(name, description, acl, handler, id, input, true);
  }

  public <C, O> McpTool query(NoArgRef<C, O> handler, String id) {
    return cell.reference(name, description, acl, handler, id, null, true);
  }

  /** A tool whose arguments are one record. */
  public static final class Arguments<A> {

    private final ToolBuilder tool;
    private final Class<A> type;

    private Arguments(ToolBuilder tool, Class<A> type) {
      this.tool = tool;
      this.type = type;
    }

    public McpTool handle(Function<? super A, ?> handler) {
      return tool.cell.arguments(tool.name, tool.description, tool.acl, type, handler);
    }
  }
}
