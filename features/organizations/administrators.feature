Feature: A platform administrator acts on every organization
  The installation's issuer names who is a platform administrator. A platform administrator may act
  on any organization without being one of its members, and what they do is recorded as done by a
  platform administrator.

  Background:
    Given an organization "acme" whose owner is "ada"
    And "root" is a platform administrator and not a member of "acme"

  Scenario: a platform administrator is shown every organization
    Given an organization "globex"
    When "root" lists the organizations
    Then "root" is shown "acme" and "globex"

  Scenario: a platform administrator adds an owner to an organization they are not a member of
    When "root" adds "bob" to "acme" as an owner
    Then "bob" is an owner of "acme"
    And what the control plane recorded of the change names "root" as its actor, acting as a platform administrator
