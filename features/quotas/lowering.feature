Feature: Lowering a quota stops nothing
  A quota may be lowered below what an organization already uses. Nothing running is stopped or
  changed: what is new is refused until the usage is under the quota again, and a service applied
  again with no more instances than it had is always accepted, so that a member can work their way
  down.

  Background:
    Given an organization "acme" of which "bob" is a member
    And "acme" has 3 running services, "cart", "orders" and "billing", each with 2 minimum instances

  Scenario: a quota lowered below the usage is accepted and stops nothing
    When the platform administrator "root" sets the quota of "acme" to 1 service
    Then "acme" has a quota of 1 service
    And "cart", "orders" and "billing" are running, unchanged

  Scenario: a new service is refused while the usage is over the quota
    Given "acme" has a quota of 1 service
    When "bob" applies a descriptor for the new service "search"
    Then "bob" is refused, and the refusal names the quota of 1 service and the 3 in use
    And "search" does not exist

  Scenario Outline: a service applied again with no more instances than it had is accepted while the usage is over the quota
    Given "acme" has a quota of 1 service and 2 instances
    When "bob" applies "cart" again with <minimum> minimum instances
    Then "cart" is applied

    Examples:
      | minimum |
      | 1       |
      | 2       |
