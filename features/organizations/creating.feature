Feature: Creating an organization
  A person who creates an organization is its first owner. An installation says who may create one:
  anyone who is signed in, unless it says that only a platform administrator may. A platform
  administrator creating an organization for someone else names its first owner, in the same
  request.

  Scenario: a person who creates an organization is its first owner
    Given an installation where anyone signed in may create an organization
    When "ada" creates the organization "acme"
    Then "acme" exists
    And "ada" is the owner of "acme"
    And "ada" is shown "acme" in a list of the organizations

  Scenario: where only a platform administrator creates organizations, anyone else is refused and told so
    Given an installation where only a platform administrator may create an organization
    When "ada", who is not a platform administrator, creates the organization "acme"
    Then "ada" is refused
    And the refusal says that organizations here are created by a platform administrator
    And "acme" does not exist

  Scenario: the refusal to create an organization names where to get one, when the installation gives an address
    Given an installation where only a platform administrator may create an organization, and whose address to get one is "https://ankka.example/signup"
    When "ada", who is not a platform administrator, creates the organization "acme"
    Then "ada" is refused
    And "ada" is shown "https://ankka.example/signup"

  Scenario: where only a platform administrator creates organizations, a platform administrator creates one
    Given an installation where only a platform administrator may create an organization
    When the platform administrator "root" creates the organization "acme"
    Then "acme" exists

  Scenario Outline: where only a platform administrator creates organizations, an owner does everything else as before
    Given an installation where only a platform administrator may create an organization
    And an organization "acme" whose owner is "ada", who is not a platform administrator, with the project "shop"
    When "ada" <does>
    Then "ada" is not refused

    Examples:
      | does                                                  |
      | lists the organizations                               |
      | reads "acme"                                          |
      | renames "acme"                                        |
      | invites "bob@example.com" into "acme"                 |
      | applies a descriptor for the service "cart" in "shop" |

  Scenario: an owner deletes their organization where only a platform administrator creates organizations
    Given an installation where only a platform administrator may create an organization
    And an organization "acme" with no project, whose owner is "ada", who is not a platform administrator
    When "ada" deletes "acme"
    Then "acme" does not exist

  Scenario: a control plane told an unknown answer to who may create organizations does not start
    Given the platform setting "ANKKA_ORGANIZATION_CREATION" is "everyone"
    When the control plane starts
    Then the control plane does not start
    And the reason names "ANKKA_ORGANIZATION_CREATION" and its values "open" and "platform-admin"

  Scenario: a platform administrator creates an organization for the owner it names
    When the platform administrator "root" creates the organization "acme" for the owner whose subject is "ada-123", whose email is "ada@example.com" and whose name is "Ada"
    Then "acme" exists
    And the members of "acme" are exactly "ada-123" as its owner, shown as "Ada" with the email "ada@example.com"
    And "root" is not a member of "acme"

  Scenario: the owner an organization was created for may act on it as its owner
    Given the platform administrator "root" created the organization "acme" for the owner whose subject is "ada-123"
    When "ada-123" lists the organizations
    Then "ada-123" is shown "acme"
    And "ada-123" may invite "bob@example.com" into "acme"

  Scenario: a platform administrator creating an organization for nobody named is its first owner
    When the platform administrator "root" creates the organization "acme"
    Then "root" is the owner of "acme"

  Scenario: a person who is not a platform administrator may not create an organization for someone else
    Given an installation where anyone signed in may create an organization
    When "bob", who is not a platform administrator, creates the organization "acme" for the owner whose subject is "ada-123"
    Then "bob" is refused
    And "acme" does not exist

  Scenario: an owner named by subject alone is shown by subject
    When the platform administrator "root" creates the organization "acme" for the owner whose subject is "ada-123", with no email and no name
    Then "acme" exists
    And the members of "acme" show "ada-123" as its owner, by subject

  Scenario: an organization created for a named owner keeps its members when the control plane restarts
    Given the platform administrator "root" created the organization "acme" for the owner whose subject is "ada-123"
    When the control plane restarts
    Then the members of "acme" are exactly "ada-123" as its owner
