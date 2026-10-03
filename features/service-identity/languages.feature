Feature: An authenticated route in every language
  A route declared authenticated works the same whether the service is written in Scala, Python,
  TypeScript or Rust, because the platform verifies the token and tells the handler the principal.
  A service that declares one and lists no issuer does not start.

  Background:
    Given an issuer "customers" that signs tokens for the audience "shop"

  Scenario Outline: an authenticated route admits a verified token in every language
    Given a service "orders" written in "<language>" that lists the issuer "customers" with the audience "shop"
    And an authenticated route of "orders"
    When a person sends a request with a token from "customers" whose subject is "ada" and whose roles are "buyer"
    Then the handler is run
    And the handler is told a principal whose subject is "ada" and whose roles are "buyer"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: an authenticated route challenges a token that does not verify in every language
    Given a service "orders" written in "<language>" that lists the issuer "customers" with the audience "shop"
    And an authenticated route of "orders"
    When a person sends a request with a token from "customers" that has expired
    Then the request is challenged
    And the handler is not run

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a claim reaches the handler by name in every language
    Given a service "orders" written in "<language>" that lists the issuer "customers" with the audience "shop"
    And an authenticated route of "orders"
    When a person sends a request with a token from "customers" that carries the claim "tier" with the value "gold"
    Then the handler is told a principal whose claim "tier" is "gold"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a service with an authenticated route and no issuer listed does not start
    Given a service "orders" written in "<language>" that lists no issuer
    And an authenticated route of "orders"
    When "orders" starts
    Then "orders" does not start
    And the reason names the route and the variable "ANKKA_AUTH_ISSUERS"

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario: a service written in Scala with an authenticated route and no issuer listed does not start
    Given a service "orders" written in "Scala" that lists no issuer
    And an authenticated route of "orders"
    When "orders" starts
    Then "orders" does not start
    And the reason names the variable "ANKKA_AUTH_ISSUERS"

  Scenario: the missing issuer is reported together with every other problem found in the service
    Given a service "orders" written in "Python" that lists no issuer
    And an authenticated route of "orders"
    And a component of "orders" with a problem of its own
    When "orders" starts
    Then "orders" does not start
    And the reason names the route and the other problem together

  Scenario: a module is not shown its issuer settings
    Given a service "orders" written in "Rust" that lists the issuer "customers" with the audience "shop"
    When "orders" asks for the variable "ANKKA_AUTH_ISSUERS"
    Then "orders" is told that the variable is not set
