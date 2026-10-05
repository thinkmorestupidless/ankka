Feature: A module calling another service as itself
  A module reaches nothing but the platform, so a handler of a module calls another service by
  asking the platform's own program to make the call. The platform makes it with the service's
  certificate, as it does for a process, so the service called reads the calling workload as the
  module's service and its ACL can admit that service and nothing else.

  Background:
    Given a module "rewards"
    And a service "wallet" that "rewards" can reach

  Scenario: a module's consumer is admitted by name by a route that admits only its service
    Given a deployed module "rewards" in the project "payments"
    And a deployed service "wallet" in the project "payments" whose route "POST /internal/credits" admits only "rewards"
    And a consumer of "rewards" that calls that route for every event it reads
    When the consumer reads an event
    Then the call is admitted
    And the handler of "wallet" reads the calling workload as the service "rewards" of the project "payments"

  Scenario: the answer of the service called reaches a module's handler as the service made it
    Given a consumer of "rewards" that calls "wallet" for every event it reads
    When the consumer reads an event
    Then the consumer is given the answer of "wallet" as "wallet" made it

  Scenario: a refusal by the service called reaches a module's handler as that refusal
    Given a consumer of "rewards" that calls "wallet" for every event it reads
    And the ACL of "wallet" refuses "rewards"
    When the consumer reads an event
    Then the consumer is given the refusal as "wallet" made it
    And the consumer does not fail

  Scenario: a module's call to a service that cannot be found fails, naming the service, and is not sent
    Given a consumer of "rewards" that calls "ledger" for every event it reads
    And no service "ledger" that "rewards" can reach
    When the consumer reads an event
    Then the consumer is told that the service "ledger" cannot be found
    And no call is sent to any service

  Scenario Outline: a module calls another service from every handler that may wait
    Given <handler> of "rewards" that calls "wallet"
    When the platform runs it
    Then "wallet" reads the calling workload as the service "rewards"
    And it is given the answer of "wallet"

    Examples:
      | handler                    |
      | a step of a workflow       |
      | a consumer                 |
      | a timed action             |
      | a handler of an agent      |
      | a tool of an agent         |
      | a guardrail of an agent    |
      | a result check of an agent |
      | a route of an endpoint     |

  Scenario: a call made from a module's step is nested under the step in the trace
    Given a workflow of "rewards" whose step "pay-out" called "wallet"
    When the trace of the workflow is read
    Then the trace of the workflow has the call to "wallet" nested under the step "pay-out"

  Scenario: a module's handler that waits for another service longer than the platform waits for the handler fails
    Given a service "slow" that answers after longer than the platform waits for a consumer
    And a consumer of "rewards" that calls "slow" for every event it reads
    When the consumer reads an event
    Then the consumer fails
    And the failure says that the module gave no answer in time
