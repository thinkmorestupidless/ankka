package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.GraphConsumer;
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffect;
import java.util.List;
import java.util.Map;

/** The key value entity as a graph: its node at each state, its tombstone after. */
@ComponentId("profile-graph")
public class ProfileGraph extends GraphConsumer<Profile.ProfileState> {

  @Override
  protected ChangeSource<Profile.ProfileState> source() {
    return ChangeSource.of(Profile.class);
  }

  @Override
  protected String topic() {
    return "conformance-profile-graph";
  }

  @Override
  protected GraphEffect onChange(Profile.ProfileState state) {
    var id = "profile:" + messageContext().subject();
    return effects().publish(graph().node(id, List.of("Profile"), Map.of("name", state.name())));
  }

  @Override
  protected GraphEffect onDelete() {
    return effects().publish(graph().tombstoneNode("profile:" + messageContext().subject()));
  }
}
