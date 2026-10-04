Feature: An exposed service that serves gRPC
  An exposed service is called from outside the cluster at its hostname. The one hostname answers
  HTTP requests and gRPC calls alike, and every call from outside the cluster comes from the
  gateway.

  Scenario: a call to an exposed service's hostname is answered, and its calling workload is the gateway
    Given a deployed service "cart" that serves gRPC and is exposed
    When a developer calls the method "GetCart" at the hostname of the service "cart"
    Then the call ends with the status "ok"
    And the handler for the method "GetCart" reads the calling workload as the gateway

  Scenario: a request to a route at the hostname of a service that serves gRPC is answered by its HTTP endpoint
    Given a deployed service "cart" that serves gRPC and is exposed
    And the service "cart" has an HTTP endpoint with the route "GET /carts/{cartId}"
    When a developer sends a request to the route "GET /carts/{cartId}" at the hostname of the service "cart"
    Then the request is answered by the HTTP endpoint of the service "cart"

  Scenario: an endpoint that admits only a named service refuses a call from outside the cluster
    Given a deployed service "cart" that is exposed
    And the service "cart" has a gRPC endpoint whose ACL admits only the service "checkout"
    When a developer calls the method "GetCart" at the hostname of the service "cart"
    Then the call ends with the status "permission denied"
    And no handler runs

  Scenario: an endpoint that admits the gateway admits a call from outside the cluster
    Given a deployed service "cart" that is exposed
    And the service "cart" has a gRPC endpoint whose ACL admits only the gateway
    When a developer calls the method "GetCart" at the hostname of the service "cart"
    Then the call ends with the status "ok"

  Scenario: a service that declares gRPC and is not exposed answers no call from outside the cluster
    Given a deployed service "cart" that serves gRPC and is not exposed
    When a developer calls the method "GetCart" of the service "cart" from outside the cluster
    Then no handler runs

  Scenario: an exposed service that does not declare gRPC is exposed as it was before
    Given a deployed service "orders" that is exposed and whose descriptor does not declare gRPC
    When the platform is upgraded to a version that serves gRPC
    Then the hostname of the service "orders" serves the routes of its HTTP endpoints
    And no instance of the service "orders" is restarted

  Scenario: an exposed service that serves gRPC and no HTTP answers a gRPC call at its hostname
    Given a deployed service "cart" that is exposed and whose descriptor declares gRPC and declares no HTTP
    When a developer calls the method "GetCart" at the hostname of the service "cart"
    Then the call ends with the status "ok"

  Scenario: a stream reaches a developer outside the cluster a part at a time
    Given a deployed service "cart" that serves gRPC and is exposed
    And the handler for the method "WatchCart" answers with a stream that produces "3" parts, one each second
    When a developer calls the method "WatchCart" at the hostname of the service "cart"
    Then the developer is given each part as it is produced

  Scenario: a method that takes a stream and answers with a stream is called from outside the cluster
    Given a deployed service "cart" that serves gRPC and is exposed
    And the handler for the method "Converse" takes a stream and answers each part it reads with one part
    When a developer calls the method "Converse" at the hostname of the service "cart" and sends "1" part without ending the stream
    Then the developer is given "1" part

  Scenario: a member is shown why an exposed service's gRPC cannot be reached at its hostname
    Given a deployed service "cart" that serves gRPC and is exposed
    And the gateway has not accepted the service "cart"
    When a member reads the service "cart"
    Then the member is shown that the gateway has not accepted the service "cart"
    And the member is shown the gateway's reason

  Scenario: an exposed service that opts into reflection answers a tool outside the cluster
    Given a deployed service "cart" that serves gRPC and is exposed
    And the service "cart" opts into reflection with an ACL that admits only the gateway
    When a developer asks for reflection at the hostname of the service "cart"
    Then the developer is told the methods of the service definitions of the service "cart"
