Feature: An installation trusting an issuer hosted elsewhere
  An installation need not run an issuer of its own. Its control plane can list an issuer hosted by
  another installation, so that one set of people signs in to several installations, and it trusts
  that issuer alone.

  Background:
    Given an installation "second" whose control plane lists the issuer of the installation "first"
    And "second" runs no issuer of its own

  Scenario: a control plane whose issuer is hosted elsewhere starts with no issuer of its own
    When the control plane of "second" starts
    Then the control plane of "second" is running

  Scenario: a member signing in to an installation is sent to the issuer it lists
    When a member signs in to "second"
    Then the member is sent to the issuer of "first"
    And the member is signed in to "second"

  Scenario: a token from an issuer hosted elsewhere is admitted and its holder recorded as the actor
    Given "ada" is a member of the organization "acme" on "second"
    When "ada" pauses the service "cart" of "acme" on "second" with a token from the issuer of "first"
    Then the service "cart" is paused
    And the history of "cart" names "ada" as the actor of the pause

  Scenario: a token from an issuer the control plane does not list is challenged
    When a member sends a request to the control plane of "second" with a token from an issuer at the hostname of "second"
    Then the request is challenged
