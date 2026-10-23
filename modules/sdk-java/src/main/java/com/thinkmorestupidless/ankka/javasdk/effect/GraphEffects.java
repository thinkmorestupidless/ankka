package com.thinkmorestupidless.ankka.javasdk.effect;

import java.util.List;

/** The effects a graph consumer's handlers return. */
public interface GraphEffects {

  /** Publish these elements as deltas, in order. */
  GraphEffect publish(GraphElement... elements);

  GraphEffect publish(List<GraphElement> elements);

  GraphEffect done();

  GraphEffect ignore();
}
