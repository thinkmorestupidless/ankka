Feature: What a judgment cost and who answered it
  A session reports the usage of its judgments beside the usage of its model, and never adds the
  two together: they are priced far apart, and their sum would mean nothing. Every judgment says
  which model version answered it.

  Scenario: an answered judgment says which model version answered it and what it cost
    Given a judgment that the judgment provider answered
    When the judgment is read
    Then it carries the model version that answered it
    And it carries the usage the judgment provider reported

  Scenario: a session reports its judgments' usage apart from its model's
    Given a session in which a handler's judgment and a judged guardrail were each answered
    When the session's usage is read
    Then the usage of the judgments is reported as a figure of its own
    And the usage of the model does not include it

  Scenario: the usage of a judged guardrail that refused is still counted
    Given a session in which a judged guardrail refused a request
    When the session's usage is read
    Then the usage of the guardrail's judgment is counted
    And no message of the request was recorded

  Scenario: the usage of an autonomous agent's judged guardrail is counted on its task's session
    Given a task whose instructions an autonomous agent's judged guardrail checked
    When the usage of the task's session is read
    Then the usage of the guardrail's judgment is counted there, apart from the model's

  Scenario: a session recorded before judgments reads as it did
    Given a session recorded by a version of the platform that had no judgments
    When the session is read
    Then it reads as it did, with no usage of judgments
