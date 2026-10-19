Feature: A service's MCP endpoint offers its tools
  A service declares an MCP endpoint: a path on the service's HTTP port, an ACL, and tools, each with
  a name, a description, typed arguments and a handler. Any MCP client that reaches the path is
  told the tools and can call them; a tool call runs the handler as a request runs a route. The
  MCP endpoint is served wherever the service's routes are, by whichever instance receives the
  request, and keeps nothing between requests.

  Background:
    Given a service "cart" with an MCP endpoint at "/mcp" whose tools are "get_cart", "add_item" and "checkout"
    And the ACL of the MCP endpoint allows all

  Scenario: a service's MCP endpoint lists the tools it declares, with each tool's description and arguments
    When an MCP client asks the MCP endpoint for its tools
    Then the MCP client is told the tools "get_cart", "add_item" and "checkout"
    And each tool is told with the description and the arguments it declares

  Scenario: a tool call runs the tool's handler with typed arguments and answers its result
    When an MCP client calls "get_cart" with the arguments "cartId c1"
    Then the handler of "get_cart" is run with the cart "c1"
    And the MCP client is told what the handler answered as the result of the tool call

  Scenario: a tool's handler that refuses answers the caller a tool error carrying the reason
    Given the handler of "add_item" refuses a quantity of "0" saying "quantity must be positive"
    When an MCP client calls "add_item" with the arguments "cartId c1, quantity 0"
    Then the MCP client is told "quantity must be positive" as an error of the tool call

  Scenario: a call to a tool the MCP endpoint does not have is a protocol error naming the tool
    When an MCP client calls "empty_cart"
    Then the MCP client is told a protocol error naming "empty_cart"
    And no handler is run

  Scenario: a tool call whose arguments do not match the tool's schema is refused before the handler runs
    When an MCP client calls "add_item" with the arguments "cartId c1, quantity many"
    Then the MCP client is told a protocol error saying what is wrong with "quantity"
    And the handler of "add_item" is not run

  Scenario: a stream an MCP client opens on an MCP endpoint is closed at once, since the MCP endpoint pushes nothing
    When an MCP client opens a stream on the MCP endpoint
    Then the stream is accepted and closed
    And the MCP client does not wait

  Scenario: an MCP endpoint's path cannot also be a route's path
    Given a service "shop" with an HTTP endpoint whose route is "POST /mcp" and an MCP endpoint at "/mcp"
    When "shop" starts
    Then "shop" does not start
    And the reason names the MCP endpoint and the route "POST /mcp"

  Scenario Outline: a tool the MCP endpoint cannot declare is refused at startup, naming the MCP endpoint and the tool
    Given a service "shop" with an MCP endpoint that declares <tool>
    When "shop" starts
    Then "shop" does not start
    And the reason names the MCP endpoint and the tool

    Examples:
      | tool                                     |
      | two tools named "get_cart"               |
      | a tool "get_cart" with no description    |
      | a tool named "Get Cart"                  |
      | a tool whose arguments have no schema    |

  Scenario: a command of an entity declared as a tool is called with the entity's id taken from a named argument
    Given the tool "book_job" of the MCP endpoint of "cart" is the command "book" of the event sourced entity "job", with the id passed as "jobId"
    When an MCP client calls "book_job" with the arguments "jobId j1, slot thursday-morning"
    Then the command "book" of the entity "job" with the id "j1" is called with "slot thursday-morning"
    And the MCP client is told the command's reply as the result of the tool call

  Scenario: a query of an entity declared as a tool answers what the query answers
    Given the tool "get_job" of the MCP endpoint of "cart" is the query "get" of the event sourced entity "job", with the id passed as "jobId"
    When an MCP client calls "get_job" with the arguments "jobId j1"
    Then the MCP client is told what the query "get" of the entity "job" with the id "j1" answers

  Scenario: a route declared as a tool takes the route's path names and body as its arguments
    Given the tool "todays_jobs" of the MCP endpoint of "cart" is the route "GET /jobs/{day}" of an HTTP endpoint of "cart"
    When an MCP client asks the MCP endpoint for its tools
    Then the tool "todays_jobs" is told with the argument "day"
    And a call to "todays_jobs" with the arguments "day today" is served as a request to "GET /jobs/{day}" with the day "today" is

  Scenario: a tool declared by reference is offered as a tool with a handler is
    Given the tool "get_job" of the MCP endpoint of "cart" is the query "get" of the event sourced entity "job", with the id passed as "jobId" and the description "Reads one job"
    When an MCP client asks the MCP endpoint for its tools
    Then the MCP client is told the tool "get_job" with the description "Reads one job" and the argument "jobId"
    And nothing the MCP client is told says whether "get_job" has a handler or is a query

  Scenario Outline: a tool declared by reference to a handler or route the service does not have is refused at startup
    Given a service "shop" with an MCP endpoint whose tool "ghost" is <reference>
    When "shop" starts
    Then "shop" does not start
    And the reason names the MCP endpoint and the tool "ghost"

    Examples:
      | reference                                                          |
      | the command "vanish" of the event sourced entity "job", which has no such command |
      | the route "GET /nowhere" of an HTTP endpoint of "shop", which has no such route    |

  Scenario: a tool declared by reference without a description is refused at startup
    Given a service "shop" with an MCP endpoint whose tool "get_job" is the query "get" of the event sourced entity "job" with no description
    When "shop" starts
    Then "shop" does not start
    And the reason names the MCP endpoint and the tool "get_job"

  Scenario: a request to an MCP endpoint is answered by whichever instance receives it
    Given "cart" is deployed with 3 instances
    When an MCP client calls "get_cart" at each instance in turn
    Then each instance answers the tool call itself
    And the MCP endpoint asks the MCP client to keep nothing between the calls

  Scenario Outline: an MCP endpoint is served at every hostname the service answers at
    Given "cart" is deployed and exposed with the custom hostname "shop.example.com"
    When an MCP client asks for the tools of "cart" at <hostname>
    Then the MCP client is told the tools "get_cart", "add_item" and "checkout"

    Examples:
      | hostname                      |
      | the hostname the platform derived |
      | "shop.example.com"            |

  Scenario: a paused service's MCP endpoint answers nothing
    Given "cart" is deployed and paused
    When an MCP client asks for the tools of "cart"
    Then the MCP client is told nothing, as a client of a route of "cart" is
