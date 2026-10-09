Feature: A grant to another organization is accepted before it takes effect
  A grant whose grantee belongs to the grantor's own organization is accepted when it is made. One
  whose grantee belongs to another organization is pending, opens nothing, and is offered to that
  organization: an owner there accepts or declines it. The grantor withdraws a pending grant and
  revokes an accepted one without asking; the grantee's organization relinquishes an accepted one
  without asking; and every change is recorded on both sides with the owner who made it, so that
  no organization is recorded as holding access it never agreed to hold.

  Background:
    Given an organization "eitheror" whose owner is "ada", with the project "spinvibe"
    And the topic "affiliates.attribution" is declared on "spinvibe"
    And an organization "affiliates" whose owner is "bo"
    And "bo" has registered "network" as a machine of "affiliates"

  Scenario: a grant to another organization's principal is pending and opens nothing
    When "ada" grants the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe"
    Then the grant is shown as "pending" in the grants of "spinvibe"
    And the credential of "network" on the broker may not read "spinvibe.affiliates.attribution"

  Scenario: an owner of the grantee organization accepts a pending grant and it takes effect
    Given "ada" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe"
    And the grant is offered to "affiliates", naming the project "spinvibe" of "eitheror" and the topic "affiliates.attribution"
    When "bo" accepts the grant offered to "affiliates" by "spinvibe"
    Then within "120" seconds the grant is in effect
    And the credential of "network" on the broker may read "spinvibe.affiliates.attribution"

  Scenario: a declined grant never takes effect
    Given "ada" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe"
    When "bo" declines the grant offered to "affiliates" by "spinvibe"
    Then the grant is shown as "declined" in the grants of "spinvibe"
    And the credential of "network" on the broker may not read "spinvibe.affiliates.attribution"
    And the grant is no longer offered to "affiliates"

  Scenario: the grantor withdraws a pending grant
    Given "ada" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe"
    When "ada" withdraws the grant
    Then the grant is shown as "withdrawn" in the grants of "spinvibe"
    And the grant is no longer offered to "affiliates"

  Scenario: the grantee relinquishes an accepted grant without the grantor
    Given "ada" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe"
    And "bo" has accepted the grant
    When "bo" relinquishes the grant held by "affiliates" from "spinvibe"
    Then within "120" seconds the credential of "network" on the broker may not read "spinvibe.affiliates.attribution"
    And the grant is shown as "relinquished" in the grants of "spinvibe"
    And nobody of "eitheror" acted

  Scenario: the grantor revokes an accepted grant without the grantee
    Given "ada" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe"
    And "bo" has accepted the grant
    When "ada" revokes the grant
    Then within "120" seconds the credential of "network" on the broker may not read "spinvibe.affiliates.attribution"
    And the grant is shown as "revoked" in the grants of "spinvibe"
    And nobody of "affiliates" acted

  Scenario Outline: a member who is not an owner cannot accept, nor can a deploy token
    Given "ada" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe"
    And "cy" is a member of "affiliates"
    And a deploy token of "affiliates"
    When <someone> accepts the grant offered to "affiliates" by "spinvibe"
    Then <someone> is refused
    And the grant is still "pending"

    Examples:
      | someone                            |
      | "cy"                               |
      | a machine holding the deploy token |

  Scenario: a grant within one organization takes effect without acceptance
    Given the project "payments" of "eitheror"
    And a deployed service "wallet" in "spinvibe" with an HTTP endpoint whose ACL admits granted callers, with the route "POST /v1/wallets/{player}/{currency}/deposits"
    And a deployed service "merchant" in "payments"
    When "ada" grants the service "merchant" of "payments" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    Then the grant is shown as "accepted" in the grants of "spinvibe", with nobody having accepted it
    And within "120" seconds "merchant" is served that route

  Scenario: every change to a grant is recorded on both sides with the owner who made it
    Given "ada" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe"
    And "bo" has accepted the grant
    And "ada" has since revoked the grant
    When the history of "spinvibe" and the history of "affiliates" are read
    Then each shows that "ada" granted it, that "bo" accepted it and that "ada" revoked it, each with when it was done
    And nothing in either history holds a client secret or a machine token

  Scenario: deleting a registered machine lapses every grant naming it, and a machine registered again under its name holds nothing
    Given "ada" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe"
    And "bo" has accepted the grant
    When "bo" deletes the registered machine "network"
    Then the grant is shown as "lapsed" in the grants of "spinvibe"
    And the history of "spinvibe" and the history of "affiliates" each show that the grant lapsed when "bo" deleted "network", with when it was done
    And when "bo" registers "network" as a machine of "affiliates" again, the new registered machine holds no grant
