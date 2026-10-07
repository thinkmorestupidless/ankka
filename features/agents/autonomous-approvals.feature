Feature: An autonomous agent that waits for a person's approval
  An autonomous agent working on a task waits as an agent does when the model calls a tool that
  requires approval: the tool does not run, and every subscriber is told of the approval request.
  An autonomous agent that is waiting for a person is not working, so the wait uses none of the
  task's budget, however long it is. A decision sent to the autonomous agent lets the task go on.

  Background:
    Given a service "ops" with an autonomous agent "operator"
    And a tool "restart_service" of "operator" that requires approval
    And "operator" is working on a task

  Scenario: every subscriber is told of an autonomous agent's approval request
    Given two subscribers of "operator"
    When the model calls "restart_service" with the arguments "service cart"
    Then each subscriber is told of an approval request that names the tool "restart_service" and carries the arguments "service cart"
    And the tool "restart_service" has not run

  Scenario: waiting for a decision uses none of the task's budget
    Given an approval request of "operator" awaiting a decision
    When "1" hour passes
    Then the task has not failed
    And the task has used the iterations it had used when the model called the tool, and no more
    And the model has not been asked since

  Scenario: an autonomous agent's approved tool call runs once and the task goes on
    Given an approval request of "operator" awaiting a decision
    When a person approves the approval request
    Then the tool "restart_service" runs once
    And the iteration that made the tool call is counted once
    And "operator" goes on working on the task

  Scenario: an autonomous agent's refused tool call never runs and the task goes on
    Given an approval request of "operator" awaiting a decision
    When a person refuses the approval request with the note "not during trading"
    Then the tool "restart_service" has not run
    And the model is told that the tool call was refused, with the note "not during trading"
    And "operator" goes on working on the task

  Scenario: an autonomous agent's approval request is still awaiting a decision after the service restarts
    Given an approval request of "operator" awaiting a decision
    And "ops" has since stopped without warning and started again
    When a person approves the approval request
    Then the tool "restart_service" runs once

  Scenario: cancelling a task discards its approval requests
    Given an approval request of "operator" awaiting a decision
    When the task is cancelled
    Then a person who approves the approval request is refused
    And the tool "restart_service" has not run

  Scenario: suspending and resuming an autonomous agent leaves its approval request as it was
    Given an approval request of "operator" awaiting a decision
    When "operator" is suspended and then resumed
    Then the approval request is still awaiting a decision
    And a person who approves the approval request is not refused

  Scenario: a tool approved before the service stopped runs again when its result was not recorded
    Given a person has approved an approval request of "operator"
    And "ops" stopped before the result of the tool call was recorded
    When "ops" starts again
    Then the tool "restart_service" runs again

  Scenario: an autonomous agent's approval request that expires is refused and the task goes on
    Given a tool "drain_node" of "operator" that requires approval within "30" minutes
    And an approval request of "operator" for the tool "drain_node" awaiting a decision
    When "30" minutes pass with no decision
    Then the tool "drain_node" has not run
    And the model is told that the tool call was refused, with a note that says the approval request expired
    And "operator" goes on working on the task

  Scenario: an autonomous agent shows who decided an approval request
    Given an approval request of "operator" awaiting a decision
    When "dana" refuses the approval request with the note "not during trading"
    Then "operator" shows the approval request refused by "dana", with the note "not during trading"
