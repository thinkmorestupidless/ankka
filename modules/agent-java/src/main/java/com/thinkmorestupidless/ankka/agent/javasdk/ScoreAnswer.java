package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.List;

/** The answer to a score: the expected level, each level's probability, and the confidence. */
public record ScoreAnswer(double score, List<Double> probabilities, double confidence) {

  /** The level nearest the score. */
  public int level() {
    return (int) Math.round(score);
  }
}
