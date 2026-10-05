Feature: Managing an organization's members
  An organization's owners manage its members: they list them, remove them and change a member's
  role between owner and member. An organization always keeps at least one owner, so it can never be
  left with nobody able to manage it.

  Background:
    Given an organization "acme" whose owner is "ada"

  Scenario: an owner lists every member with their role and every pending invitation with its email
    Given "bob" is a member of "acme"
    And "ada" has invited "carol@example.com" into "acme" as a member
    When "ada" lists the members of "acme"
    Then "ada" is shown "ada" as an owner and "bob" as a member
    And "ada" is shown the invitation for "carol@example.com" as pending

  Scenario: an owner made a member can no longer manage members and can still deploy
    Given "bob" is an owner of "acme"
    And "ada" has made "bob" a member of "acme"
    When "bob" invites "carol@example.com" into "acme"
    Then "bob" is refused
    And "bob" may still apply a descriptor in a project of "acme"

  Scenario Outline: the last owner of an organization stays an owner
    When <someone> <change>
    Then the change is refused
    And the refusal says that "ada" is the last owner of "acme"
    And "ada" is still an owner of "acme"

    Examples:
      | someone                    | change                        |
      | "ada"                      | removes "ada" from "acme"     |
      | "ada"                      | makes "ada" a member of "acme" |
      | a platform administrator   | removes "ada" from "acme"     |

  Scenario Outline: a member who is not an owner may not manage members
    Given "bob" is a member of "acme"
    When "bob" <change>
    Then "bob" is refused
    And the members of "acme" are unchanged

    Examples:
      | change                                 |
      | invites "carol@example.com" into "acme" |
      | removes "ada" from "acme"              |
      | makes "bob" an owner of "acme"          |

  Scenario: an owner may not delete an organization that still has projects
    Given "acme" has the project "shop"
    When "ada" deletes "acme"
    Then "ada" is refused
    And the refusal says that "acme" still has projects
    And "acme" still exists
