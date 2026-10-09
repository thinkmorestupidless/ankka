Feature: Granting a route to a service of another project
  A project opens one of its routes to one service of another project with a grant, which an
  owner of its organization makes and revokes as data, with neither service redeployed. The
  route's author decides whether it may ever be granted: its ACL admits granted callers, or no
  grant on it opens anything. A grant names one service and one route, or one method, and nothing
  else; a service with no grant reaches nothing a grant would open.

  Background:
    Given the projects "spinvibe" and "payments" of the organization "eitheror"
    And a deployed service "wallet" in "spinvibe"
    And a deployed service "merchant" in "payments"
    And a deployed service "psp-gateway" in "payments"
    And "wallet" has an HTTP endpoint whose ACL admits granted callers, with the route "POST /v1/wallets/{player}/{currency}/deposits"

  Scenario: a service of another project is refused a route nobody granted it
    When "merchant" sends a request to the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    Then "merchant" is refused
    And no handler runs
    And the request is recorded as refused and not as failed
    And the refusal says nothing of which grants exist

  Scenario: a service of another project is served a route its project granted it
    Given an owner of "eitheror" has granted the service "merchant" of "payments" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    And "120" seconds have passed
    When "merchant" sends a request to the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    Then the handler runs
    And the handler reads the calling workload as the service "merchant" of the project "payments"
    And no instance of "wallet" or of "merchant" was restarted

  Scenario: a grant to one service admits no other service of its project
    Given an owner of "eitheror" has granted the service "merchant" of "payments" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    When "psp-gateway" sends a request to the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    Then "psp-gateway" is refused
    And no handler runs

  Scenario: a revoked grant refuses its service without a redeploy
    Given an owner of "eitheror" has granted the service "merchant" of "payments" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    And "merchant" has been served that route
    When the owner revokes the grant
    Then within "120" seconds "merchant" is refused that route
    And no instance of "wallet" or of "merchant" is restarted

  Scenario: a grant on a route whose ACL does not name granted callers opens nothing
    Given "wallet" has an HTTP endpoint whose ACL admits only the service "lobby", with the route "GET /v1/wallets/{player}"
    And an owner of "eitheror" has granted the service "merchant" of "payments" the route "GET /v1/wallets/{player}" of "wallet"
    When "merchant" sends a request to the route "GET /v1/wallets/{player}" of "wallet"
    Then "merchant" is refused
    And no handler runs
    And the grant is shown as not in effect, "route not grantable", in the grants of "spinvibe"

  Scenario: a grant names the route by method and path template and opens no other route of the service
    Given the endpoint has the route "GET /v1/wallets/{player}/{currency}" too
    And an owner of "eitheror" has granted the service "merchant" of "payments" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    When "merchant" sends a request to the route "GET /v1/wallets/{player}/{currency}" of "wallet"
    Then "merchant" is refused
    And no handler runs

  Scenario: a grant on a gRPC method admits that method and no other
    Given "wallet" has a gRPC endpoint for the service definition "WalletService" whose ACL admits granted callers
    And an owner of "eitheror" has granted the service "merchant" of "payments" the method "WalletService/Deposit" of "wallet"
    When "merchant" calls the method "Deposit" of "WalletService" of "wallet"
    Then the call ends with the status "ok"
    And a call from "merchant" to the method "GetBalance" of "WalletService" of "wallet" ends with the status "permission denied"

  Scenario: a revoked grant closes the streams and sockets it admitted
    Given the endpoint declares the socket route "/v1/wallets/{player}/events" and the route "GET /v1/wallets/{player}/ledger", which answers as a stream
    And an owner of "eitheror" has granted the service "merchant" of "payments" the socket route "/v1/wallets/{player}/events" of "wallet"
    And the owner has granted the service "merchant" of "payments" the route "GET /v1/wallets/{player}/ledger" of "wallet"
    And "merchant" holds a socket open to "/v1/wallets/{player}/events" of "wallet" and is being answered a stream by "GET /v1/wallets/{player}/ledger"
    When the owner revokes both grants
    Then within "120" seconds the socket is closed and the stream is ended
    And "merchant" is refused when it opens the socket again

  Scenario: a service deployed before the feature gains its grants volume on its next rollout
    Given "wallet" was deployed by a platform too old to hand grants to a running instance
    And an owner of "eitheror" has granted the service "merchant" of "payments" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    And the grant is shown as not in effect, "rollout needed", in the grants of "spinvibe"
    When a member applies the descriptor of "wallet" again
    Then within "120" seconds the grant is in effect
    And "merchant" is served that route
    And the status of "wallet" says its grants are mounted
