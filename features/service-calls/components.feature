Feature: Every component but an entity calls another service
  A workflow's step, an agent's tool, a consumer and a timed action call another service as the
  service they belong to, as an endpoint does. An entity does not: its handlers make no call that
  every other command to the same entity would wait behind.

  Background:
    Given a service "withdrawals" written in "Scala"
    And a service "psp-gateway" that "withdrawals" can reach

  Scenario: a workflow's step calls another service and goes on with the answer
    Given a workflow of "withdrawals" whose step "initiate-payout" calls "psp-gateway"
    When the workflow runs
    Then "psp-gateway" reads the calling workload as the service "withdrawals"
    And the step goes on with the answer of "psp-gateway"

  Scenario: an agent's tool calls another service and answers the model with what it was given
    Given an agent of "withdrawals" with a tool that calls "psp-gateway"
    When the model asks the agent for the tool
    Then "psp-gateway" reads the calling workload as the service "withdrawals"
    And the tool answers the model with the answer of "psp-gateway"

  Scenario: a consumer calls another service before the change it handles is done with
    Given a consumer of "withdrawals" that calls "psp-gateway" for every change it reads
    And "psp-gateway" fails the first call it is given
    When the consumer reads a change
    Then the consumer reads the change again
    And "psp-gateway" is given a call for the change twice

  Scenario: a timed action calls another service when its timer fires
    Given a timed action of "withdrawals" whose handler calls "psp-gateway"
    When its timer fires
    Then "psp-gateway" reads the calling workload as the service "withdrawals"

  Scenario: a workflow calls another service in a step and not in a command
    Given a workflow of "withdrawals" whose command "start" calls "psp-gateway"
    When the command "start" is sent to the workflow
    Then the command is refused, and is told that a workflow calls another service in a step
    And no call is sent to any service

  Scenario: a call made from a step is nested under the step in the trace
    Given a workflow of "withdrawals" whose step "initiate-payout" called "psp-gateway"
    When the trace of the workflow is read
    Then the trace of the workflow has the call to "psp-gateway" nested under the step "initiate-payout"

  Scenario: a step of a service that knows of no other services is told so
    Given a service "alone" written in "Scala" that knows of no other services
    And a workflow of "alone" whose step "initiate-payout" calls "psp-gateway"
    When the workflow runs
    Then the step is told that its service knows of no other services

  Scenario: a service on a developer's machine calls a service that has stopped and the call is unanswered
    Given a service "psp-gateway" that ran on a developer's machine and has stopped
    And a workflow of "withdrawals" whose step "initiate-payout" calls "psp-gateway"
    When the workflow runs
    Then the step is told that the call was unanswered
    And the step is not told that "psp-gateway" cannot be found
