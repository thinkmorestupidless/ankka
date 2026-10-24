package com.thinkmorestupidless.ankka.agent.javasdk;

/** A yes or no, built by {@link Question#yesNo}. */
public interface YesNoQuestion extends Question<YesNoAnswer> {

  /** This question, telling the model what a yes and a no each mean. */
  YesNoQuestion describing(String yes, String no);
}
