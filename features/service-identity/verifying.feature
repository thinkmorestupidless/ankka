Feature: A service verifying its users' tokens
  A service with users of its own lists the issuers whose tokens it accepts. An authenticated route
  admits a request that carries a verified token and tells its handler who the person is; a request
  without one is challenged. The service checks tokens with the issuer's keys and asks the issuer
  for nothing else.

  Background:
    Given an issuer "customers" that signs tokens for the audience "shop"
    And a service "orders" that lists the issuer "customers" with the audience "shop"
    And an authenticated route of "orders"

  Scenario: a request with a verified token reaches the handler with its principal
    When a person sends a request with a token from "customers" whose subject is "ada" and whose roles are "buyer"
    Then the handler is run
    And the handler is told a principal whose subject is "ada" and whose roles are "buyer"

  Scenario: a claim the platform does not define reaches the handler by name
    When a person sends a request with a token from "customers" that carries the claim "tier" with the value "gold"
    Then the handler is told a principal whose claim "tier" is "gold"

  Scenario: a request with no token is challenged
    When a person sends a request with no token
    Then the request is challenged
    And the handler is not run

  Scenario Outline: a token that does not verify is challenged
    When a person sends a request with a token from "customers" that <fault>
    Then the request is challenged
    And the handler is not run

    Examples:
      | fault                                                       |
      | has expired                                                 |
      | is not yet valid                                            |
      | is for the audience "warehouse"                             |
      | has no subject                                              |

  Scenario: a token from an issuer the service does not list is challenged
    Given an issuer "strangers" that signs tokens for the audience "shop"
    When a person sends a request with a token from "strangers"
    Then the request is challenged
    And the handler is not run

  Scenario: a token signed with a shared secret is challenged without its issuer being asked for keys
    When a person sends a request with a token that names "customers" and is signed with a shared secret
    Then the request is challenged
    And "customers" is not asked for its keys

  Scenario: keys already fetched go on verifying tokens while the issuer cannot be reached
    Given "orders" has fetched the keys of "customers"
    And "customers" cannot be reached
    When a person sends a request with a token from "customers"
    Then the handler is run

  Scenario: a request is answered unavailable once the tolerance has passed
    Given "orders" has fetched the keys of "customers"
    And "customers" has not been reachable for longer than the tolerance
    When a person sends a request with a token from "customers"
    Then the request is answered unavailable
    And the handler is not run

  Scenario: a service starts without waiting for its issuers' keys
    Given "customers" cannot be reached
    When "orders" starts
    Then "orders" is ready without having fetched the keys of "customers"

  Scenario: a request is answered unavailable while an issuer's keys have never been fetched
    Given "customers" cannot be reached
    And "orders" has never fetched the keys of "customers"
    When a person sends a request with a token from "customers"
    Then the request is answered unavailable

  Scenario: a request is answered unavailable rather than waiting when the keys are slow to arrive
    Given "orders" has never fetched the keys of "customers"
    And "customers" takes longer than the fetch timeout to answer for its keys
    When a person sends a request with a token from "customers"
    Then the request is answered unavailable within the fetch timeout

  Scenario: a handler reads the principal only under an ACL that asks for a token
    Given a route of "orders" whose ACL admits everyone
    When the handler of that route reads the principal
    Then the handler fails
    And the failure names the ACL

  Scenario: a token attached to a call that an ACL admits by caller is ignored
    Given a route of "orders" whose ACL admits only the service "web"
    And an issuer "strangers" that signs tokens for the audience "shop"
    When the service "web" calls that route with a token from "strangers" attached
    Then the call is admitted
    And the handler is told that the caller is the service "web"
