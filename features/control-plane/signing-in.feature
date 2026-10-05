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

  Scenario Outline: a token the control plane cannot verify is challenged, never refused
    When a member sends a request to the control plane with <token>
    Then the request is challenged
    And the request is not refused

    Examples:
      | token                                                      |
      | a token from the installation's issuer that was changed    |
      | a token from an issuer other than the installation's       |
      | a token signed with a shared secret                        |
      | a value that is not a token from any issuer                |

  Scenario: the control plane starts while the installation's issuer cannot be reached
    Given the installation's issuer cannot be reached
    When the control plane starts
    Then the control plane is running
    And a request to the control plane with a token from the installation's issuer is answered unavailable

  Scenario: a member signs in on a machine with no browser by confirming a code in a browser anywhere
    Given a member of the organization "acme" whose machine knows only the address of the control plane and the certificate it trusts
    When the member signs in
    Then the member is shown a code and an address
    And once the member confirms the code at that address in a browser on any machine, the member is signed in with nothing more asked

  Scenario: a member whose token has expired is given a new one from their sign-in without being asked
    Given a member who is signed in
    And the member's token has expired
    When the member lists the organizations
    Then the member is given a new token from their sign-in
    And the member is shown the organizations they are a member of

  Scenario: a member whose sign-in the issuer has ended is told to sign in again
    Given a member who is signed in
    And the installation's issuer has ended the member's sign-in
    When the member lists the organizations
    Then the member is told to sign in again
    And the member is shown no token

  Scenario: a machine with a token from the installation's issuer acts without signing in
    Given a machine that the installation's issuer gave a token, which is a member of the organization "acme"
    When the machine applies a descriptor for the service "cart" in the project "shop" of "acme" with that token
    Then the machine is not asked to sign in
    And the service "cart" is applied

  Scenario: a token given with one request is used rather than the member's sign-in
    Given a member who is signed in as "ada"
    And a token from the installation's issuer for the machine "deployer"
    When the member lists the organizations with the token for "deployer"
    Then the control plane admits the request as "deployer"
