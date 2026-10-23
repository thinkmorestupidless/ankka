package com.thinkmorestupidless.ankka.agent.javasdk;

/** One option of a choice: the service's value, its wire key, and what the model reads. */
public record ChoiceOption<T>(T value, String key, String description) {

  public static <T> ChoiceOption<T> of(T value, String key, String description) {
    return new ChoiceOption<>(value, key, description);
  }

  /** An option whose value is its key. */
  public static ChoiceOption<String> of(String key, String description) {
    return new ChoiceOption<>(key, key, description);
  }
}
