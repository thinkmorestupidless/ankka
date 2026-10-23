package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.Map;

/** The answer to a choice: the option chosen, every option's probability, and the confidence. */
public record ChoiceAnswer<T>(T choice, Map<T, Double> probabilities, double confidence) {}
