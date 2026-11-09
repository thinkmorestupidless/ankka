package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Players;

/** The personal data page's test, through the Java kit's keyring. */
public final class PersonalChecks {

  private PersonalChecks() {}

  // docs:start erase
  /** Registers a player, erases them, and reads the email back after a restart. */
  static String emailAfterErasure(AnkkaTestKit kit) {
    var players = kit.componentClient();
    players.forEventSourcedEntity("8c1f").call(Players::register).invoke("ada@example.com");

    kit.erase("player/8c1f"); // destroys the key now and sends the notice
    kit.restartService(); // read from the database, not from memory: a fresh client, too
    var restarted = kit.componentClient();
    var email = restarted.forEventSourcedEntity("8c1f").call(Players::email).invoke();

    kit.assertNoPersonalValue("ada@example.com"); // fails naming the table and column holding it
    return email; // "erased"
  }
  // docs:end erase
}
