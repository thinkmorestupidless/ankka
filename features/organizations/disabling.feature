Feature: Disabling an organization
  A platform administrator may disable an organization: every service running in it stops and is
  suspended, and its members may read but change nothing. Enabling it again brings back exactly what
  was running before, and leaves paused what its members had paused.

  Background:
    Given an organization "acme" whose owner is "ada" and of which "bob" is a member
    And the project "shop" of "acme", in which the service "cart" is running and the service "orders" is paused

  Scenario: disabling an organization stops and suspends its running services and leaves its paused ones paused
    When the platform administrator "root" disables "acme"
    Then "cart" is stopped and shown as suspended
    And "orders" is shown as paused
    And "acme" is shown as disabled

  Scenario Outline: a member of a disabled organization may change nothing in it
    Given "root" has disabled "acme"
    When <who> <changes>
    Then the change is refused
    And the refusal says that "acme" is disabled

    Examples:
      | who   | changes                                         |
      | "bob" | resumes "orders"                                |
      | "bob" | applies a descriptor for the service "cart"     |
      | "ada" | creates the project "store" in "acme"           |
      | "ada" | invites "carol@example.com" into "acme"         |

  Scenario Outline: a member of a disabled organization may still read it
    Given "root" has disabled "acme"
    When "bob" reads <what>
    Then "bob" is shown <what>

    Examples:
      | what                  |
      | the service "cart"    |
      | the logs of "cart"    |
      | the history of "cart" |

  Scenario: enabling an organization brings back what was running and leaves paused what its members paused
    Given "root" has disabled "acme"
    When "root" enables "acme"
    Then "cart" is running
    And "orders" is shown as paused

  Scenario Outline: an owner may not disable or enable their own organization
    Given <state>
    When "ada" <change> "acme"
    Then "ada" is refused
    And "acme" is shown as <shown>

    Examples:
      | state                     | change   | shown        |
      | "acme" is not disabled    | disables | not disabled |
      | "root" has disabled "acme" | enables  | disabled     |
