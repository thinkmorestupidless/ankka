package com.thinkmorestupidless.ankka.javasdk.mcp;

/** A key just issued: its id, and the key itself, shown this once. */
public record IssuedClientKey(String id, String clientKey) {
  @Override
  public String toString() {
    return "IssuedClientKey[" + id + ", <the key>]";
  }
}
