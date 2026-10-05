Feature: A result is held to its task type's rules
  A task type may declare rules a result must satisfy beyond its shape. A result a rule refuses does
  not complete the task: the task is result-rejected, the model is told why, and it may try again
  within the same budget.

  Background:
    Given an autonomous agent "answerer" that accepts the task type "answer"
    And the task type "answer" has the rules "cites a source" and "is shorter than 500 words", declared in that order

  Scenario: a result a rule refuses leaves the task result-rejected and tells the model why
    Given a task of the type "answer" in progress on an agent instance of "answerer"
    When the model completes the task with a result that cites no source
    Then the task is result-rejected with the reason the rule "cites a source" gave
    And the model is told that reason when it is next called

  Scenario: the rules are checked in their declared order and the first refusal is the one reported
    Given a task of the type "answer" in progress on an agent instance of "answerer"
    When the model completes the task with a result that cites no source and is 600 words long
    Then the task is result-rejected with the reason the rule "cites a source" gave
    And the reason the rule "is shorter than 500 words" would give is not reported

  Scenario: a result-rejected task is completed by a result every rule accepts
    Given a task of the type "answer" that is result-rejected
    When the model completes the task with a result that cites a source and is 200 words long
    Then the task is completed with that result
