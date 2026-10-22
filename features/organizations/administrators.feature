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

  Scenario: a platform administrator creates a deploy token for an organization they are not a member of
    Given an organization "acme" of which the platform administrator is not a member
    When the platform administrator creates a project token for the project "orders" of "acme"
    Then the project token is shown once and acts in "orders"

  Scenario: a platform administrator accepts a pending grant on an organization's behalf
    Given a grant offered to a service of "acme" that is pending
    When the platform administrator accepts the grant for "acme"
    Then the grant takes effect

  Scenario: an organization's history shows an administrator's act under the role that allowed it
    Given a project token created and a grant accepted for "acme" by the platform administrator
    When an owner of "acme" reads the organization's history
    Then each act is attributed to the platform administrator under the role of a platform administrator
