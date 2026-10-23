package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Action;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.TimedAction;
import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffect;

/** A timed action that records on the conformance entity it is given. */
@ComponentId("reminder")
public class Reminder extends TimedAction {

  @Action("remind")
  public TimedActionEffect remind(String id) {
    record(id, "reminded");
    return effects().done();
  }

  /** Records the due time it was run for, as the runtime told it. */
  @Action("tick")
  public TimedActionEffect tick(String id) {
    record(id, "due:" + context().dueTime().toEpochMilli());
    return effects().done();
  }

  private void record(String id, String what) {
    context()
        .componentClient()
        .forEventSourcedEntity(id)
        .call(ConformanceEntity::record)
        .invoke(what);
  }
}
