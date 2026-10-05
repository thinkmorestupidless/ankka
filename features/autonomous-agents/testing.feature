Feature: Testing an autonomous agent with a scripted model
  A developer tests an autonomous agent with the scripted model the test kit offers. The script says
  what the model answers; a script that runs out fails the test rather than leaving a task waiting.

  Background:
    Given an autonomous agent "answerer" that accepts the task type "answer"
    And a test of "answerer" with a scripted model

  Scenario: a script that completes a task completes it with a result of the task type
    Given a script that completes the task with an answer "Paris" and a confidence of 0.9
    When the test runs a task of the type "answer" on "answerer"
    Then the task is completed with a result of the task type holding the answer "Paris" and the confidence 0.9

  Scenario Outline: a script conditioned on what happened answers only when it matches
    Given a script with one answer for <matching> and another for <other>
    When the test runs a task in which <matching> happens
    Then the model answers with the first of them
    And the other is left in the script

    Examples:
      | matching                                         | other                                          |
      | the model being asked about "France"             | the model being asked about "Spain"            |
      | the tool "lookup" giving back "Paris"            | the tool "lookup" giving back "Madrid"         |

  Scenario: a test waiting for a task is given the task as it ended
    Given a script that completes the task
    When the test waits for the task to end
    Then the test is given the task's record as it ended

  Scenario: a test waiting for a task that does not end fails naming the task's last status
    Given a script that never completes or gives up on the task
    When the test waits for the task to end, for at most "2 seconds"
    Then the test fails, naming the task's last status

  Scenario: a script that runs out fails the task and the test sees why
    Given a script with one answer
    When the agent instance calls the model a second time
    Then the task is failed with a reason naming the script that ran out
    And the test is shown that failure
