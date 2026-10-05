Feature: An autonomous agent's work survives its process
  Every iteration is recorded as it happens: the model's reply before its tools run, and the
  tools' results before the next call to the model. An agent instance whose process stops carries on
  from what was recorded, on whichever machine of the cluster now hosts it, and no recorded call to
  the model is made again. A tool may therefore run more than once.

  Background:
    Given an autonomous agent "answerer" that accepts the task type "answer"

  Scenario: an agent instance whose process stops finishes its task after the restart
    Given an agent instance of "answerer" with a task in progress and 3 iterations recorded
    When the service's process stops and the service restarts
    Then the agent instance carries on with the task from its last recorded iteration
    And the task is completed without the caller doing anything
    And the model is called once for each iteration that was not recorded

  Scenario: an agent instance's queued tasks survive a restart in their order
    Given an agent instance of "answerer" with a task in progress and the tasks "t2" and "t3" queued in that order
    When the service's process stops and the service restarts
    Then "t2" and "t3" are still queued in that order
    And they are worked after the task in progress

  Scenario: tools whose results were not recorded run again from the recorded reply
    Given an agent instance of "answerer" whose model's reply asking for tools has been recorded
    And one of those tools has run and its result has not been recorded
    When the service's process stops and the agent instance carries on after the restart
    Then the tools are run again from the recorded reply
    And the model is not called again for that iteration

  Scenario: a task carries on when its agent instance moves in a rolling update
    Given an agent instance of "answerer" with a task in progress
    When a rolling update moves the agent instance to another machine of the cluster
    Then the task carries on there
    And no iteration is lost or recorded twice
