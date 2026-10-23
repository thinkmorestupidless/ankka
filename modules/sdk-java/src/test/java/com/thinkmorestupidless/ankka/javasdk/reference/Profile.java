package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

// docs:start key-value-entity
/** A key value entity holding a name. */
@ComponentId("profile")
public class Profile extends KeyValueEntity<Profile.ProfileState> {

  @Manifest("profile")
  public record ProfileState(String name) {}

  @Override
  protected ProfileState emptyState() {
    return new ProfileState("");
  }

  @Command("set")
  public Effect<String> set(String name) {
    if (name.isEmpty()) return effects().error("a name is needed");
    return effects().updateState(new ProfileState(name)).thenReply("done");
  }

  @Query("get")
  public ReadOnlyEffect<String> get() {
    return effects().reply(currentState().name().isEmpty() ? "none" : currentState().name());
  }

  @Command("delete")
  public Effect<String> delete() {
    return effects().deleteEntity().thenReply("done");
  }
}
// docs:end key-value-entity
