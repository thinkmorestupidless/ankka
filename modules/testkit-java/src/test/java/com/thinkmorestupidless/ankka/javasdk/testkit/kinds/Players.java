package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.DataSubject;
import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.Personal;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import java.util.Optional;

/** {@code players} in Java (feature 042): a registration whose email is a personal field. */
@ComponentId("player")
public class Players extends EventSourcedEntity<Players.Player, Players.PlayerRegistered> {

  public record Player(Optional<Personal<String>> email) {}

  // docs:start marking
  @Manifest("player-registered")
  public record PlayerRegistered(String playerId, Personal<String> email, String currency) {}
  // docs:end marking

  @Override
  protected Player emptyState() {
    return new Player(Optional.empty());
  }

  @Override
  protected Player applyEvent(Player state, PlayerRegistered event) {
    return new Player(Optional.of(event.email()));
  }

  // docs:start marking

  @Command("register")
  public Effect<Done> register(String email) {
    var id = context().entityId();
    var subject = DataSubject.of("player/" + id);
    return effects().persist(new PlayerRegistered(id, Personal.of(subject, email), "EUR")).thenDone();
  }
  // docs:end marking

  /** The email, {@code erased} once the player is, or {@code none}. */
  @Query("email")
  public ReadOnlyEffect<String> email() {
    return effects().reply(currentState().email().map(e -> e.orElse("erased")).orElse("none"));
  }
}
