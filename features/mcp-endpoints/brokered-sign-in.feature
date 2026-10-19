Feature: Brokered sign-in for a service's MCP endpoint
  An MCP client that can only sign in needs an authorization server that will register it. A
  service whose issuer will not may opt an MCP endpoint into brokered sign-in: the platform then
  runs the authorization server, at the service's own hostnames. It registers the client, sends
  the person to the service's issuer to sign in, and mints a token for this service alone, which
  the MCP endpoint verifies as it verifies any token. The sign-in broker holds no account and no password.

  Background:
    Given an issuer "customers" that signs tokens for the audience "shop"
    And a service "orders" that lists the issuer "customers" with the audience "shop"
    And an MCP endpoint of "orders" whose tool is "list_orders" and whose ACL admits tokens

  Scenario: a service that opts into brokered sign-in names the platform's sign-in broker in its resource metadata
    Given "orders" opts the MCP endpoint into brokered sign-in
    When an MCP client reads the resource metadata of "orders"
    Then the resource metadata names the sign-in broker of "orders", at a hostname of "orders"
    And the resource metadata does not name the issuer "customers"

  Scenario: an MCP client registers itself with the sign-in broker and is sent to the service's issuer to sign in
    Given "orders" opts the MCP endpoint into brokered sign-in
    When an MCP client registers itself with the sign-in broker of "orders" and asks to sign in
    Then the MCP client is registered
    And the person is sent to the issuer "customers" to sign in

  Scenario: a token the sign-in broker mints runs the tool as the subject the service's issuer gave
    Given "orders" opts the MCP endpoint into brokered sign-in
    And an MCP client has signed in through the sign-in broker of "orders" as the person "ada" of the issuer "customers"
    When the MCP client calls "list_orders" with the token the sign-in broker minted
    Then the handler of "list_orders" is told a principal whose subject is "ada"

  Scenario: a token the sign-in broker minted for one service opens nothing on another
    Given "orders" opts the MCP endpoint into brokered sign-in
    And a service "billing" that lists the issuer "customers" with the audience "shop", with an MCP endpoint whose ACL admits tokens
    And an MCP client has signed in through the sign-in broker of "orders" as the person "ada" of the issuer "customers"
    When the MCP client calls a tool of "billing" with the token the sign-in broker of "orders" minted
    Then the request is challenged
    And no handler of "billing" is run

  Scenario: a person the service's issuer does not sign in gets no token from the sign-in broker
    Given "orders" opts the MCP endpoint into brokered sign-in
    And the issuer "customers" refuses to sign in the person "mallory"
    When an MCP client asks the sign-in broker of "orders" to sign in as "mallory"
    Then the MCP client is told the sign-in failed
    And no token is minted

  Scenario: a service that has not opted into brokered sign-in has no sign-in broker
    When an MCP client asks the sign-in broker of "orders" to register it
    Then the MCP client finds no sign-in broker
    And the resource metadata of "orders" names the issuer "customers" and no sign-in broker

  Scenario: the sign-in broker holds no password and the service's issuer is never asked for one
    Given "orders" opts the MCP endpoint into brokered sign-in
    When an MCP client has signed in through the sign-in broker of "orders" as the person "ada" of the issuer "customers"
    Then "orders" holds no password of "ada"
    And the issuer "customers" was asked for nothing but to sign "ada" in
