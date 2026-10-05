Feature: The budget of a task
  A task type says how many iterations an agent instance may spend on one task. The model is told
  how many remain as the budget nears its end, and a task the model has neither completed nor given
  up on when the budget is spent is failed, so an unattended agent cannot spend without end.

  Background:
    Given an autonomous agent "answerer" that accepts the task type "answer" with a budget of 3 iterations

  Scenario: a task still unfinished when its budget is spent is failed and the next task starts
    Given an agent instance of "answerer" with a task in progress and another task queued
    When 3 iterations pass in which the model neither completes nor gives up on the task
    Then the task is failed with a reason naming its budget of 3 iterations
    And the agent instance starts its queued task

  Scenario Outline: the model is told how many iterations remain as the budget nears its end
    Given "answerer" also accepts the task type "research" with a budget of 10 iterations
    And an agent instance of "answerer" with a task of the type "research" in progress
    When the model is called for iteration <iteration>
    Then the model is told that this is iteration <iteration> of 10
    And the model is warned <warning>

    Examples:
      | iteration | warning                                  |
      | 7         | of nothing                               |
      | 8         | that 2 iterations remain after this one  |
      | 9         | that 1 iteration remains after this one  |
      | 10        | that this is its last iteration          |

  Scenario Outline: a task that fails leaves the queued tasks to run in their order
    Given an agent instance of "answerer" with a task in progress and the tasks "t2" and "t3" queued in that order
    When the task in progress is failed because <cause>
    Then "t2" is worked and then "t3"

    Examples:
      | cause                             |
      | its budget is spent               |
      | the model gives up on it          |
      | its input guardrail refuses it    |

  Scenario: a task failed by its budget has no result
    Given a task that was failed because its budget was spent
    When a caller reads the task
    Then the caller is shown that the task is failed
    And the reason names the budget
    And no result is shown
