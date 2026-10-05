Feature: One service sending requests to another as itself
  A service sends requests to another service by name, or by project and name, as itself: it shows
  its own certificate and checks the other's, and its developer handles no key and no setting. On a
  developer's machine the same code reaches the other service running there.

  Scenario: a service sends a request to another of its project by name, as itself
    Given a deployed service "cart" in the project "shop"
    And a deployed service "checkout" in the project "shop"
    When "checkout" sends a request to "cart" by name
    Then the request reaches "cart" over a mutually authenticated connection
    And the handler reads the calling workload as the service "checkout" of the project "shop"

  Scenario: a service sends a request to a service of another project by project and name, as itself
    Given a deployed service "cart" in the project "shop"
    And a deployed service "reports" in the project "finance"
    When "reports" sends a request to "cart" of the project "shop"
    Then the handler reads the calling workload as the service "reports" of the project "finance"

  Scenario: a request is not sent to a workload that is not the service asked for
    Given a deployed service "checkout"
    And a workload at the address of "cart" whose certificate names the service "orders"
    When "checkout" sends a request to "cart" by name
    Then the request fails, and "checkout" is shown that the workload is not the service "cart"
    And nothing of the request is sent to the workload

  Scenario: on a developer's machine a service sends a request to another running there by name
    Given a service "cart" running on a developer's machine
    And a service "checkout" running on a developer's machine
    When "checkout" sends a request to "cart" by name
    Then the request is answered by "cart"
