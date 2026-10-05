Feature: An organization's usage follows what exists
  An organization keeps an exact count of its projects, its services and their minimum instances.
  Applying a service again with fewer instances counts fewer, with more counts more, and deleting a
  project or a service frees what it held. What is paused or suspended is still the organization's,
  and still counted.

  Background:
    Given an organization "acme" of which "bob" is a member, with the project "shop"
    And "acme" has a quota of 4 instances

  Scenario: a service applied again with fewer instances frees what it no longer needs
    Given the service "cart" in "shop" with 2 minimum instances
    When "bob" applies "cart" again with 1 minimum instance
    Then "acme" has a usage of 1 instance
    And "bob" may apply a new service "orders" with 3 minimum instances

  Scenario: a service applied again with more instances than the quota allows is refused, and keeps its descriptor
    Given the service "cart" in "shop" with 2 minimum instances
    And the service "orders" in "shop" with 1 minimum instance
    When "bob" applies "cart" again with 4 minimum instances
    Then "bob" is refused, and the refusal names the quota of 4 instances
    And "cart" still has 2 minimum instances

  Scenario: deleting a service frees its service and its instances
    Given the service "cart" in "shop" with 2 minimum instances
    And "acme" has a usage of 1 service and 2 instances
    When "bob" deletes "cart"
    Then "acme" has a usage of 0 services and 0 instances

  Scenario: deleting a project frees the project
    Given "acme" has the project "lab", with no service
    And "acme" has a usage of 2 projects
    When "bob" deletes "lab"
    Then "acme" has a usage of 1 project

  Scenario Outline: a stopped service is still counted
    Given the service "cart" in "shop" with 2 minimum instances
    And <stopped>
    When "bob" reads "acme"
    Then "bob" is shown a usage of 1 service and 2 instances

    Examples:
      | stopped                                     |
      | "cart" is paused                            |
      | the platform administrator has disabled "acme" |

  Scenario: an organization's usage is the same after the control plane restarts
    Given the service "cart" in "shop" with 2 minimum instances
    When the control plane restarts
    Then "acme" has a usage of 1 project, 1 service and 2 instances
