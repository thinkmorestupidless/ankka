package com.thinkmorestupidless.ankka.javasdk.fixtures.broken;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

/** A handler whose parameter has no serializer. */
@ComponentId("no-serializer")
public class NoSerializer extends KeyValueEntity<String> {
  public static final class Payload {
    public String text = "";
  }

  @Command("set")
  public Effect<Done> set(Payload p) {
    return effects().updateState(p.text).thenDone();
  }
}
