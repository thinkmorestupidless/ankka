Feature: A service that lists several issuers
  One service may front an operator realm and a customer realm. It lists both issuers, each with
  its own audience, and a token is verified against the keys and the audience of the issuer that
  signed it.

  Background:
    Given an issuer "staff" that signs tokens for the audience "backoffice"
    And an issuer "customers" that signs tokens for the audience "shop"
    And a service "orders" that lists the issuer "staff" with the audience "backoffice" and the issuer "customers" with the audience "shop"
    And an authenticated route of "orders"

  Scenario: a token is verified against the keys and the audience of the issuer that signed it
    When a person sends a request with a token from "customers"
    Then the handler is run
    And the handler is told a principal whose issuer is "customers"

  Scenario: a token that names one issuer and is signed with another's keys is challenged
    When a person sends a request with a token that names "customers" and is signed with the keys of "staff"
    Then the request is challenged
    And the handler is not run

  Scenario: a token for one issuer's audience is not accepted from the other issuer
    When a person sends a request with a token from "staff" for the audience "shop"
    Then the request is challenged

  Scenario: one issuer that cannot be reached does not stop the other's tokens being admitted
    Given "orders" has fetched the keys of "staff"
    And "customers" cannot be reached
    When a person sends a request with a token from "staff"
    Then the handler is run

  Scenario: an issuer listed twice stops the service starting
    Given a service "returns" that lists the issuer "customers" twice
    When "returns" starts
    Then "returns" does not start
    And the reason names the issuer "customers"

  Scenario: an issuer with a setting missing stops the service starting
    Given a service "returns" that lists the issuer "customers" with no audience
    When "returns" starts
    Then "returns" does not start
    And the reason names the issuer "customers" and the audience

  Scenario Outline: a token's type is checked only for an issuer that asks for it
    Given a service "returns" that lists the issuer "staff" and asks for the type "Bearer" on its tokens
    And "returns" lists the issuer "customers" without asking for a type
    And an authenticated route of "returns"
    When a person sends a request with a token from "<issuer>" whose type is "<type>"
    Then the request is <outcome>

    Examples:
      | issuer    | type   | outcome    |
      | staff     | Bearer | admitted   |
      | staff     | Other  | challenged |
      | customers | Other  | admitted   |
      | customers | none   | admitted   |
