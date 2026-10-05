Feature: Judged guardrails
  A judged guardrail asks questions about the text going into or coming out of a model, and refuses
  when an answer crosses the threshold its developer set. It stands in the same list as guardrails
  that are not judged, on agents and autonomous agents alike, and its refusal is theirs. A judged
  guardrail that could not ask has refused nothing: the interaction does not go on, and its caller
  can tell that from a refusal.

  Background:
    Given an agent "support" of a service written in "Scala"
    And a judged guardrail "override-attempt" on the input of "support" asking the yes or no question "overrides-instructions" with a threshold of 0.7

  Scenario Outline: an input whose answer reaches the threshold is refused before the model is called
    Given the judgment provider answers "overrides-instructions" with a probability of <probability>
    When a request reaches "support"
    Then the request is refused naming the guardrail "override-attempt" and the question "overrides-instructions"
    And the model is not called
    And nothing is written to the session's conversation

    Examples:
      | probability |
      | 0.7         |
      | 0.95        |

  Scenario: an input whose answer is below the threshold goes on to the model
    Given the judgment provider answers "overrides-instructions" with a probability of 0.69
    When a request reaches "support"
    Then the model is called with the request

  Scenario: a reply a judged guardrail on output refuses is not remembered
    Given a judged guardrail "medical-advice" on the output of "support" asking the yes or no question "gives-medical-advice" with a threshold of 0.5
    And the judgment provider answers "gives-medical-advice" of the model's reply with a probability of 0.8
    When a request reaches "support"
    Then the reply is refused
    And the reply is not written to the session's conversation

  Scenario: a judged guardrail asks all its questions in one request and names the first that refuses
    Given a judged guardrail "tone" on the input of "support" asking "abusive", "threatening" and "spam", declared in that order
    And the judgment provider answers "threatening" and "spam" above their thresholds
    When a request reaches "support"
    Then one request holding the three questions is sent to the judgment provider
    And the request is refused naming the question "threatening"

  Scenario: a guardrail that refuses first spares the judged guardrail after it
    Given a guardrail that is not judged, declared before "override-attempt", refusing input longer than 1000 characters
    When a request of 2000 characters reaches "support"
    Then the request is refused by the guardrail that is not judged
    And no judgment provider is asked

  Scenario Outline: a judged guardrail refuses a refused option or a level reached
    Given a judged guardrail "routing" on the input of "support" asking <question> that refuses <rule>
    And the judgment provider answers <answer>
    When a request reaches "support"
    Then the request is refused naming the guardrail "routing"

    Examples:
      | question                         | rule                                  | answer                                 |
      | the choice question "topic"      | the options "legal" and "medical"     | "topic" with the option "legal"        |
      | the score question "severity"    | at or above the level "high"          | "severity" with the level "high"       |
      | the score question "severity"    | at or above the level "high"          | "severity" with the level "critical"   |

  Scenario Outline: a judged guardrail that could not ask stops the interaction without refusing it
    Given the judgment provider <fault>
    When a request reaches "support"
    Then the model is not called
    And the caller is told that the check could not be made, which is not a refusal

    Examples:
      | fault                                         |
      | fails                                         |
      | does not answer within the judgment's time limit |

  Scenario: an autonomous agent's judged guardrail checks a task's instructions before the model is called
    Given an autonomous agent "researcher" with the judged guardrail "override-attempt" on its input
    And the judgment provider answers "overrides-instructions" of the task's instructions with a probability of 0.9
    When a task starts on an agent instance of "researcher"
    Then the model is not called
    And the task is failed with the guardrail's reason, as a refusal by a guardrail that is not judged fails it

  Scenario: an autonomous agent's judged guardrail on output sends a refused result back to the model
    Given an autonomous agent "researcher" with a judged guardrail "medical-advice" on its output
    And the judgment provider answers "gives-medical-advice" of the result with a probability of 0.9
    When the model completes a task with that result
    Then the task is result-rejected with the guardrail's reason
    And the model is told that reason when it is next called

  Scenario: a judged guardrail on a stream keeps a refused reply out of the conversation it cannot recall
    Given a handler of "support" that answers as a stream
    And a judged guardrail "medical-advice" on the output of "support" that refuses the reply
    When the stream ends
    Then the caller has been given the whole reply
    And the reply is not written to the session's conversation
