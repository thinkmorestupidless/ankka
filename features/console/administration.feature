Feature: What a platform administrator does through the console
  Through the console a platform administrator disables and enables an organization and sets and
  clears its quota.

  Background:
    Given a platform administrator signed in to the console

  Scenario: a disabled organization's services are suspended
    Given the organization "acme" with the service "cart" running in the project "shop"
    When the platform administrator disables "acme"
    Then "acme" is shown as disabled
    And the lifecycle of "cart" is "Suspended"

  Scenario: enabling an organization brings back what was running
    Given the organization "acme", disabled while the service "cart" was running in the project "shop"
    When the platform administrator enables "acme"
    Then "acme" is not shown as disabled
    And "cart" is running again

  Scenario Outline: an organization is shown with the quota in force
    Given the organization "acme" <before>
    When the platform administrator <action>
    Then "acme" is shown with <after>

    Examples:
      | before                             | action                                      | after                     |
      | with no quota                      | sets the quota of "acme" to "5" projects    | a quota of "5" projects   |
      | with a quota of "5" projects       | clears the quota of "acme"                  | no quota                  |
