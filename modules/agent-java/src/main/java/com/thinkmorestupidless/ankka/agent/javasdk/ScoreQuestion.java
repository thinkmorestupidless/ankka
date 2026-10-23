package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.List;

/** A score over ordered levels, built by {@link Question#score}. */
public interface ScoreQuestion extends Question<ScoreAnswer> {

  List<String> levels();
}
