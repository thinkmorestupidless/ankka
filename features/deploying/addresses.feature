Feature: The address of a deployed service
  A deployed service that serves HTTP is given an address inside the cluster, which reaches the
  port its instances listen on. The two are said once, so they cannot differ. An instance is ready
  only once it listens.

  Scenario Outline: the address of a service reaches the port its instances listen on
    Given a descriptor for the service "cart" that <states>
    When a member applies the descriptor
    Then the instances of "cart" listen on the HTTP port "<port>"
    And a request to the address of "cart" inside the cluster reaches the HTTP port "<port>"

    Examples:
      | states                        | port |
      | states no HTTP port           | 9000 |
      | states the HTTP port "8080"   | 8080 |

  Scenario: an instance that is not yet listening is not ready
    Given a deployed service "cart"
    When an instance of "cart" has started and is not yet listening on its HTTP port
    Then the instance is not ready
    And no request is sent to the instance

  Scenario: a service that serves no HTTP has no address and is ready once it runs
    Given a descriptor for the service "worker" that declares no HTTP
    When a member applies the descriptor
    Then "worker" has no address inside the cluster and no HTTP port
    And "worker" is ready once its instance is running
