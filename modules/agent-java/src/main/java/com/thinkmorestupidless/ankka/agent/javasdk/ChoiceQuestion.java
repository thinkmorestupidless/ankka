package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.List;

/** A choice among options, built by {@link Question#choice}. */
public interface ChoiceQuestion<T> extends Question<ChoiceAnswer<T>> {

  List<ChoiceOption<T>> options();
}
