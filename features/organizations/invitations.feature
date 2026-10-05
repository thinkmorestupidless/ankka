Feature: Inviting people into an organization
  An owner invites a person by email address. The invitation becomes a membership the next time a
  person whose verified email is that address sends a request; until then it is pending, and an
  owner may revoke it.

  Background:
    Given an organization "acme" whose owner is "ada"

  Scenario: an invitation is claimed by the next request from a person whose verified email matches
    Given "ada" has invited "bob@example.com" into "acme" as a member
    When "bob", whose verified email is "bob@example.com", lists the organizations
    Then "bob" is shown "acme"
    And the members of "acme" show "bob" as a member and no pending invitation for "bob@example.com"

  Scenario: an invitation is not claimed by a person whose email is not verified
    Given "ada" has invited "bob@example.com" into "acme" as a member
    When "mallory", whose email "bob@example.com" is not verified, lists the organizations
    Then "mallory" is shown nothing of "acme"
    And the members of "acme" show the invitation for "bob@example.com" as pending

  Scenario: an owner may not invite an email address that is already a member's
    Given "bob", whose verified email is "bob@example.com", is a member of "acme"
    When "ada" invites "bob@example.com" into "acme"
    Then "ada" is refused
    And the refusal says that "bob@example.com" is already a member of "acme"

  Scenario: a revoked invitation is never claimed
    Given "ada" has invited "bob@example.com" into "acme" as a member
    And "ada" has revoked the invitation for "bob@example.com"
    When "bob", whose verified email is "bob@example.com", lists the organizations
    Then "bob" is shown nothing of "acme"
