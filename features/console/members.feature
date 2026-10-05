Feature: Members and deploy tokens through the console
  Through the console an owner sees who is in an organization and which invitations are pending,
  invites someone by email, makes a member an owner or not, removes a member, and creates and
  revokes deploy tokens. A member who is not an owner sees the same members and alters none of
  them.

  Background:
    Given an owner of the organization "acme"

  Scenario: an owner is shown every member of an organization and every pending invitation
    Given "acme" has a member with a name and an email, and a pending invitation for "ana@example.test"
    When the owner reads the members of "acme"
    Then the owner is shown each member's subject, name and email where they are known, and whether each is an owner
    And the owner is shown the pending invitation for "ana@example.test"

  Scenario: an invitation is pending until it is claimed
    When the owner invites "ana@example.test" to "acme"
    Then the owner is shown an invitation for "ana@example.test" as pending

  Scenario: a person who signs in with an invited email is a member of the organization
    Given the owner has invited "ana@example.test" to "acme"
    When a person signs in to the console with the email "ana@example.test", which the installation's issuer has confirmed
    Then the person is shown "acme" among the organizations they belong to

  Scenario Outline: what an owner does to a member takes effect on the member's next request
    Given "acme" has the member "bo", <before>
    When the owner <action>
    Then the next request "bo" makes is answered as <after>

    Examples:
      | before             | action                          | after                                  |
      | who is not an owner | makes "bo" an owner            | an owner's                             |
      | who is an owner    | makes "bo" no longer an owner   | a member's who is not an owner         |
      | who is not an owner | removes "bo" from "acme"       | someone's who is not a member of "acme" |

  Scenario Outline: the last owner of an organization cannot be removed or made a member who is not an owner
    Given the owner is the only owner of "acme"
    When the owner <action>
    Then the owner is refused
    And the refusal says that "acme" would have no owner

    Examples:
      | action                                      |
      | removes themselves from "acme"              |
      | makes themselves no longer an owner         |

  Scenario: a member who is not an owner is shown the members and offered nothing to do to them
    Given a member of "acme" who is not an owner
    When the member reads the members of "acme"
    Then the member is shown every member of "acme"
    And the member is offered nothing to do to any of them

  Scenario: a deploy token's membership is shown as a machine's
    Given a deploy token "ci" of "acme"
    When the owner reads the members of "acme"
    Then the membership of "ci" is shown as a machine's

  Scenario: a deploy token is shown once, when it is created
    When the owner creates a deploy token "ci" for "acme"
    Then the owner is shown the deploy token once, with a warning that it will not be shown again
    And a list of the deploy tokens of "acme" afterwards shows "ci" with its id, its name and when it was created, and not the deploy token itself

  Scenario: a revoked deploy token is refused
    Given a deploy token "ci" of "acme"
    When the owner revokes "ci"
    Then the next request a machine makes with "ci" is refused
