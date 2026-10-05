Feature: Driving an agent instance from outside
  An agent instance is named by an id its caller chooses. A caller assigns it tasks, which it works
  one at a time in the order assigned; suspends and resumes it; cancels a task; terminates it for
  good; and asks it what it is doing. Each takes effect at the end of an iteration, so a call to the
  model or a tool already running is never interrupted.

  Background:
    Given an autonomous agent "answerer" that accepts the task type "answer"
    And an agent instance of "answerer" with the id "desk-1"

  Scenario: tasks assigned to an agent instance are worked one at a time in the order assigned
    When the tasks "t1", "t2" and "t3" are assigned to "desk-1" in that order
    Then "desk-1" works "t1", then "t2", then "t3", one at a time

  Scenario: a task assigned to an agent instance waits for the tasks it depends on
    Given a task "t2" that depends on a task "t1" that is not yet completed
    When "t2" is assigned to "desk-1"
    Then "desk-1" does not start "t2" until "t1" is completed

  Scenario: a suspended agent instance calls no model and keeps its tasks
    Given "desk-1" is working the task "t1" with the task "t2" queued
    When "desk-1" is suspended
    Then the iteration already running finishes
    And the model is not called again
    And "t1" stays in progress and "t2" stays queued

  Scenario: a resumed agent instance carries on from its next iteration
    Given "desk-1" was suspended while working the task "t1"
    When "desk-1" is resumed
    Then "desk-1" starts the next iteration of "t1"

  Scenario: a terminated agent instance gives its tasks back unassigned and refuses any more
    Given "desk-1" is working the task "t1" with the task "t2" queued
    When "desk-1" is terminated
    Then "desk-1" stops at the end of the iteration already running
    And "t1" and "t2" are unassigned, with a note that their agent instance was terminated
    And "t1" and "t2" can still be read
    And any task assigned to "desk-1" from then on is refused

  Scenario Outline: an agent instance tells a caller what it is doing
    Given "desk-1" <situation>
    When a caller asks "desk-1" what it is doing
    Then the caller is shown that "desk-1" is <phase>
    And the caller is shown the task it is working, the tasks queued in their order, the iterations spent on the task it is working, and its usage in total

    Examples:
      | situation                                         | phase      |
      | has no task                                       | idle       |
      | is working the task "t1" with the task "t2" queued | working    |
      | was suspended while working the task "t1"         | suspended  |
      | was terminated                                    | terminated |

  Scenario: a task cancelled while in progress stops at the end of the iteration and the next task starts
    Given "desk-1" is working the task "t1" with the tasks "t2" and "t3" queued in that order
    When a caller cancels "t1" with the reason "no longer needed"
    Then "desk-1" stops working "t1" at the end of the iteration already running
    And "t1" is cancelled with the reason "no longer needed"
    And "desk-1" starts "t2"

  Scenario: a queued task that is cancelled leaves the queue at once
    Given "desk-1" is working the task "t1" with the tasks "t2", "t3" and "t4" queued in that order
    When a caller cancels "t3"
    Then "t3" is cancelled at once
    And "t2" and "t4" stay queued in that order

  Scenario: an agent instance with nothing to do holds nothing but its record until it is next addressed
    Given "desk-1" has finished every task it was given
    And nobody is watching "desk-1"
    When "desk-1" is left alone
    Then "desk-1" holds nothing but its record until it is next addressed
    And "desk-1", next addressed, has everything it had before
