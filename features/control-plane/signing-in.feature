Feature: The control plane verifying members' tokens
  The control plane verifies a member's token exactly as a service verifies its users', with the
  same verifier, and nothing a member sees changes.

  Background:
    Given the installation's issuer signs tokens for the control plane's audience

  Scenario: a member with a verified token is admitted by the control plane
    When a member sends a request to the control plane with a token from the installation's issuer
    Then the control plane admits the request as that member

  Scenario: a request to the control plane with no token is challenged
    When a member sends a request to the control plane with no token
    Then the request is challenged

  Scenario: a request to the control plane with an expired token is challenged
    When a member sends a request to the control plane with a token from the installation's issuer that has expired
    Then the request is challenged

  Scenario: a machine with a deploy token is admitted by the control plane as a member
    Given a deploy token of the organization "acme"
    When a machine sends a request to the control plane with that deploy token
    Then the control plane admits the request as a member of "acme"

  Scenario: the control plane answers unavailable once its issuer's keys cannot be fetched and the tolerance has passed
    Given the control plane has fetched the keys of the installation's issuer
    And the installation's issuer has not been reachable for longer than the tolerance
    When a member sends a request to the control plane with a token from the installation's issuer
    Then the request is answered unavailable
