Feature: Seeing a service's MCP endpoints and their tool calls
  What a service offers over its MCP endpoints is part of what the platform shows of it, beside its
  routes, and a tool call is traced as a request to a route is: a span for the MCP endpoint and the
  tool, carrying the principal's subject, with the calls the handler made nested under it.

  Background:
    Given a service "cart" with an MCP endpoint at "/mcp" whose tools are "get_cart" and "add_item"
    And an HTTP endpoint of "cart" whose route is "GET /carts/{cartId}"

  Scenario: a service's report names its MCP endpoints and their tools
    Given "cart" is deployed
    When a member reads the service "cart"
    Then the report names the MCP endpoint "/mcp" with the tools "get_cart" and "add_item" beside the route "GET /carts/{cartId}"
    And the console shows the same

  Scenario: a tool call is traced under its MCP endpoint and tool with the principal's subject
    Given the ACL of the MCP endpoint admits client keys
    And a client key of "cart" for the subject "acme"
    And "cart" has handled a tool call to "get_cart" carrying the client key, whose handler called the event sourced entity "cart"
    When the developer reads the trace of the request in the local console
    Then the trace shows a span for the MCP endpoint "/mcp" and the tool "get_cart" whose subject is "acme"
    And the trace shows the call to "cart" nested under it
    And nothing in the trace reads as the client key
