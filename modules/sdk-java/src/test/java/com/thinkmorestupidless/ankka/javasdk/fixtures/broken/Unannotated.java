package com.thinkmorestupidless.ankka.javasdk.fixtures.broken;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

/** A handler-shaped method with no annotation. */
@ComponentId("unannotated")
public class Unannotated extends KeyValueEntity<String> {
  public ReadOnlyEffect<String> get() {
    return effects().reply("");
  }
}
