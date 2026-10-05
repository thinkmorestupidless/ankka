Feature: An organization's quota
  A platform administrator may set a quota on an organization: at most so many projects, so many
  services and so many instances, the instances being every service's minimum instances added up.
  The control plane refuses what would take the organization past its quota when it is asked for,
  and names the quota and the usage in the refusal. An organization with no quota is unlimited.

  Background:
    Given an organization "acme" of which "bob" is a member

  Scenario Outline: an organization with no quota is refused nothing for capacity
    Given "acme" has no quota
    And "acme" has 5 projects and 10 services with 30 minimum instances between them
    When "bob" <asks>
    Then "bob" is not refused

    Examples:
      | asks                                                                     |
      | creates the project "store" in "acme"                                    |
      | applies a descriptor for the new service "orders" with 10 minimum instances |

  Scenario: a platform administrator sets an organization's quota
    When the platform administrator "root" sets the quota of "acme" to 2 projects
    Then "acme" has a quota of 2 projects

  Scenario: a member reads the quota and usage of the organization
    Given "acme" has a quota of 2 projects, 3 services and 4 instances
    And "acme" has 1 project, in which 2 services with 3 minimum instances between them are deployed
    When "bob" reads "acme"
    Then "bob" is shown a quota of 2 projects, 3 services and 4 instances
    And "bob" is shown a usage of 1 project, 2 services and 3 instances

  Scenario: a project past the organization's project quota is refused, and nothing is created
    Given "acme" has a quota of 2 projects
    And "acme" has the projects "shop" and "lab"
    When "bob" creates the project "store" in "acme"
    Then "bob" is refused
    And the refusal says that "acme" has reached its quota of 2 projects
    And "acme" has no project "store"

  Scenario: a service past the organization's service quota is refused
    Given "acme" has a quota of 3 services
    And "acme" has 3 services across its projects
    When "bob" applies a descriptor for a fourth service "orders"
    Then "bob" is refused
    And the refusal says that "acme" has reached its quota of 3 services
    And "orders" does not exist

  Scenario Outline: a service whose instances would take the organization past its instance quota is refused
    Given "acme" has a quota of 4 instances
    And the services of "acme" have 3 minimum instances between them
    When "bob" applies a descriptor for the new service "orders" with <minimum> minimum instances
    Then <outcome>

    Examples:
      | minimum | outcome                                                                                   |
      | 2       | "bob" is refused, and the refusal names the quota of 4 instances and the 3 in use         |
      | 1       | "orders" is applied                                                                       |

  Scenario Outline: only a platform administrator may set or clear a quota
    Given "acme" has a quota of 2 projects
    When "bob", who is not a platform administrator, <changes> the quota of "acme"
    Then "bob" is refused
    And "acme" still has a quota of 2 projects

    Examples:
      | changes |
      | sets    |
      | clears  |

  Scenario: a cleared quota leaves the organization unlimited
    Given "acme" has a quota of 2 projects
    When the platform administrator "root" clears the quota of "acme"
    Then "acme" has no quota
    And "bob" is shown no quota when reading "acme"
