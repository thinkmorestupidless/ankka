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

  Scenario: the local console lists a service's MCP endpoints and their tools
    Given a service "cart" running on a developer's machine with an MCP endpoint at "/mcp" whose tools are "get_cart" and "add_item"
    When a developer reads the service "cart" in the local console
    Then the local console lists the MCP endpoint "/mcp" with the tools "get_cart" and "add_item" beside the routes of "cart"
