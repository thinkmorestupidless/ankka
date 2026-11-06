package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Players;

/** Method references to the players' handlers (feature 042). */
final class PlayerRefs {

  static final CommandRef<Players, String, Done> register = Players::register;
  static final NoArgRef<Players, String> email = Players::email;

  private PlayerRefs() {}
}
