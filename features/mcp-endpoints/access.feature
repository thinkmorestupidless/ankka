Feature: Who may call a service's MCP endpoint
  An MCP endpoint states its ACL as every endpoint does, and the ACL knows three kinds of caller: a
  workload named by its certificate, a person with a verified token from one of the service's
  issuers, and the holder of a client key. An MCP endpoint that admits tokens tells a client with
  no credential where to sign in, naming the service's own issuers and never the platform's. A
  tool may state an ACL of its own.

  Background:
    Given an issuer "customers" that signs tokens for the audience "shop"
    And a service "orders" that lists the issuer "customers" with the audience "shop"
    And an MCP endpoint of "orders" whose tools are "list_orders" and "cancel_order"

  Scenario: an MCP endpoint must state its ACL
    Given a service "cart" with an MCP endpoint that states no ACL
    When "cart" starts
    Then "cart" does not start
    And the reason names the MCP endpoint and says that an ACL must be stated

  Scenario: a request with no credential at an MCP endpoint that admits tokens is told where to get one
    Given the ACL of the MCP endpoint admits tokens
    When an MCP client calls "list_orders" with no credential
    Then the request is challenged
    And the challenge names the resource metadata of "orders"
    And no handler is run

  Scenario: an MCP endpoint that admits tokens publishes the issuers a client may sign in with
    Given the ACL of the MCP endpoint admits tokens
    When an MCP client reads the resource metadata of "orders"
    Then the resource metadata names the issuer "customers"
    And the resource metadata names no issuer of the platform's

  Scenario: an MCP endpoint that admits no token publishes no issuer
    Given the ACL of the MCP endpoint admits client keys
    When an MCP client reads the resource metadata of "orders"
    Then the resource metadata names no issuer

  Scenario: a request carrying a token from one of the service's issuers runs the tool as the token's subject
    Given the ACL of the MCP endpoint admits tokens
    When an MCP client calls "list_orders" with a token from "customers" whose subject is "ada"
    Then the handler of "list_orders" is told a principal whose subject is "ada"

  Scenario: an MCP endpoint that admits named workloads admits them by certificate and asks for no credential
    Given the ACL of the MCP endpoint admits only the service "support"
    And "orders" and "support" are deployed in the project "shop"
    When "support" calls "list_orders" with no credential
    Then the handler of "list_orders" is told that the call came from the service "support" in the project "shop"

  Scenario Outline: a credential an MCP endpoint's ACL does not admit is refused
    Given the ACL of the MCP endpoint admits <admits>
    When an MCP client calls "list_orders" carrying <credential>
    Then the request is refused
    And no handler is run

    Examples:
      | admits                     | credential                                 |
      | client keys                | a token from "customers"                   |
      | tokens                     | a client key of "orders"                  |
      | only the service "support" | a client key of "orders"                  |
      | only the service "support" | a token from "customers"                   |

  Scenario: a tool's own ACL replaces the MCP endpoint's for that tool
    Given the ACL of the MCP endpoint admits tokens
    And the ACL of the tool "cancel_order" admits only the service "support"
    When an MCP client calls "cancel_order" with a token from "customers" whose subject is "ada"
    Then the request is refused
    And a call to "list_orders" with the same token is served
