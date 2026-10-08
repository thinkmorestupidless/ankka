Feature: A registered machine calling a granted route
  A machine outside the installation is registered on an organization by an owner, who is shown
  its client id and client secret once. It exchanges them at the control plane's token route for a
  machine token, which names the machine and nothing else, and calls a granted route through the
  gateway with it. The platform turns the machine token into the calling workload before the ACL
  runs, so a handler reads the machine as it reads a service; a request with no machine token is
  the gateway, as it always was. A handler verifies no machine token itself.

  Background:
    Given an organization "eitheror" whose owner is "ada"
    And a deployed service "affiliates" in the project "spinvibe" of "eitheror"
    And "affiliates" is exposed
    And "affiliates" has an HTTP endpoint whose ACL admits granted callers, with the route "GET /v1/affiliates/attribution"

  Scenario: a registered machine is shown its secret once and it is never shown again
    When "ada" registers "affiliate-network" as a machine of "eitheror"
    Then "ada" is shown the client id and the client secret of the registered machine "affiliate-network"
    And the client secret is never shown again
    And the list of the registered machines of "eitheror" shows "affiliate-network" with who registered it and when, and no client secret

  Scenario: a machine takes a token from the control plane with its client id and secret
    Given "ada" has registered "affiliate-network" as a machine of "eitheror"
    When the registered machine asks the token route for a machine token with its client id and client secret
    Then it is given a machine token that names "affiliate-network" of "eitheror" and no grant
    And the machine token expires "15" minutes after it was issued

  Scenario: a machine is served a route its grant names, as itself
    Given "ada" has registered "affiliate-network" as a machine of "eitheror"
    And "ada" has granted the registered machine "affiliate-network" of "eitheror" the route "GET /v1/affiliates/attribution" of "affiliates"
    And the registered machine holds a machine token
    When the registered machine sends a request with its machine token to the route "GET /v1/affiliates/attribution" at the hostname of "affiliates"
    Then the handler runs
    And the handler reads the calling workload as the registered machine "affiliate-network" of the organization "eitheror"

  Scenario: a machine is refused a route nobody granted it
    Given "ada" has registered "affiliate-network" as a machine of "eitheror"
    And the registered machine holds a machine token
    When the registered machine sends a request with its machine token to the route "GET /v1/affiliates/attribution" at the hostname of "affiliates"
    Then the request is refused
    And no handler runs
    And the refusal says nothing of which grants exist

  Scenario: a request from the internet with no token is not a machine
    Given "affiliates" has the route "GET /v1/affiliates/feed" whose ACL allows all
    When a person on the internet sends a request with no token to the route "GET /v1/affiliates/feed" at the hostname of "affiliates"
    Then the handler reads the calling workload as the gateway
    And the same request to "GET /v1/affiliates/attribution" is refused

  Scenario: a token from another issuer does not make its bearer a machine
    Given an issuer "strangers" that signs tokens for the audience "shop"
    And "affiliates" has the route "GET /v1/affiliates/feed" whose ACL allows all
    When a person on the internet sends a request with a token from "strangers" to the route "GET /v1/affiliates/feed" at the hostname of "affiliates"
    Then the handler reads the calling workload as the gateway
    And the same request to "GET /v1/affiliates/attribution" is refused

  Scenario: a revoked grant refuses a machine whose token has not expired
    Given "ada" has registered "affiliate-network" as a machine of "eitheror"
    And "ada" has granted the registered machine "affiliate-network" of "eitheror" the route "GET /v1/affiliates/attribution" of "affiliates"
    And the registered machine has been served that route with a machine token issued a moment ago
    When "ada" revokes the grant
    Then within "120" seconds the registered machine is refused that route with the same machine token
    And no instance of "affiliates" is restarted

  Scenario: a deleted machine is given no token
    Given "ada" has registered "affiliate-network" as a machine of "eitheror"
    When "ada" deletes the registered machine "affiliate-network"
    Then the token route refuses the client id and client secret of "affiliate-network"
    And "affiliate-network" is no longer listed among the registered machines of "eitheror"

  Scenario: a machine's token at a route that authenticates makes it a principal and a machine caller
    Given an issuer "customers" that signs tokens for the audience "shop"
    And "affiliates" lists the issuer "customers" with the audience "shop", and no issuer for machine tokens
    And "affiliates" has the authenticated route "GET /v1/affiliates/report"
    And "ada" has registered "affiliate-network" as a machine of "eitheror"
    And the registered machine holds a machine token
    When the registered machine sends a request with its machine token to the route "GET /v1/affiliates/report" at the hostname of "affiliates"
    Then the handler runs
    And the handler is told a principal whose subject names the registered machine "affiliate-network" of "eitheror"
    And the handler reads the calling workload as the registered machine "affiliate-network" of the organization "eitheror"

  Scenario: a client id asking for tokens faster than the limit is refused for a while
    Given "ada" has registered "affiliate-network" as a machine of "eitheror"
    And the registered machine holds a machine token
    When the registered machine asks the token route for a machine token more often than the installation allows
    Then the token route refuses its client id for a while
    And the machine token it already holds still admits it
