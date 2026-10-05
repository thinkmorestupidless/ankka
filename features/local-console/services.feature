Feature: The services the local console lists
  The local console lists every service running on the developer's machine, and follows services
  starting and stopping while it is open. It needs nothing to be started first.

  Scenario: the local console with no service running says it found none
    Given no service is running on a developer's machine
    When the developer starts the local console
    Then the local console says that it found no service
    And it goes on running

  Scenario: a service started while the local console is open is listed without restarting the local console
    Given the local console is open on a developer's machine with the service "cart" running
    When the service "orders" starts on that machine
    Then the local console lists "orders" beside "cart"
    And the local console was not restarted
