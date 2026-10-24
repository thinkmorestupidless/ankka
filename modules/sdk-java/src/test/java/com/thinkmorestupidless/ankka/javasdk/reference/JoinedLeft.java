package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;

/** The left of the keyed view's two sources: it counts what it noted. */
@ComponentId("joined-left")
public class JoinedLeft extends EventSourcedEntity<Integer, Noted> {

  @Override
  protected Integer emptyState() {
    return 0;
  }

  @Override
  protected Integer applyEvent(Integer state, Noted event) {
    return state + 1;
  }

  @Command("record")
  public Effect<String> record(String text) {
    return effects().persist(new Noted(text)).thenReply("recorded");
  }
}
