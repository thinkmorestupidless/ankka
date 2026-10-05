Feature: Running a task to a typed result
  An autonomous agent is given a task and iterates on it, calling its model and the tools the model
  asks for, until the model completes the task with a result of the task type's shape or gives up on
  it. Whoever holds the task's id reads the result later, or waits for it.

  Background:
    Given an autonomous agent "answerer" that accepts the task type "answer"
    And the task type "answer" has a result holding an answer and a confidence

  Scenario: running a task gives back its id before the model is called
    When a caller runs a task of the type "answer" on "answerer" with the instructions "What is the capital of France?"
    Then the caller is given the task's id before any call to the model
    And the task is recorded as assigned to a new agent instance

  Scenario: a task the model completes with a result of the task type's shape is completed
    Given a task of the type "answer" in progress on an agent instance of "answerer"
    When the model completes the task with a result of the task type's shape
    Then the task is completed with that result
    And the result can be read by the task's id
    And the iterations and the model usage the agent instance spent on the task are recorded

  Scenario: a task the model gives up on is failed with the model's reason
    Given a task of the type "answer" in progress on an agent instance of "answerer"
    When the model gives up on the task with the reason "no source answers the question"
    Then the task is failed with the reason "no source answers the question"
    And the model is not called again for the task

  Scenario: a result not of the task type's shape goes back to the model
    Given a task of the type "answer" in progress on an agent instance of "answerer"
    When the model completes the task with a result that is not of the task type's shape
    Then the task is not completed
    And the model is told why the result could not be read
    And the next iteration starts

  Scenario: a completed task tells its reader everything about how it ended
    Given a task of the type "answer" that an agent instance of "answerer" completed
    When a caller reads the task
    Then the caller is shown that the task is completed, with its typed result
    And the caller is shown when the task was created, started and completed
    And the caller is shown which agent instance completed it

  Scenario Outline: a caller waiting for a task is answered when the task ends
    Given a task of the type "answer" in progress on an agent instance of "answerer"
    And a caller waiting for the task to end
    When the task is <ending>
    Then the caller is given the task as it ended

    Examples:
      | ending    |
      | completed |
      | failed    |
      | cancelled |

  Scenario: a caller waiting for a task is told when its own time limit passes
    Given a task of the type "answer" in progress on an agent instance of "answerer"
    And a caller waiting for the task to end, for at most "5 seconds"
    When "5 seconds" pass and the task has not ended
    Then the caller is told that it stopped waiting before the task ended
