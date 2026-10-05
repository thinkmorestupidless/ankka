Feature: Loading a module
  A service written in Rust is a module the platform's own program loads into itself and calls
  directly, with no second process and no port for the developer's code. The platform loads only a
  module made for an ABI version it speaks, and refuses one it cannot host before it serves anything.

  Scenario Outline: a module the platform cannot load is refused, saying why
    Given a service "shop" whose module <problem>
    When the platform's own program of "shop" starts
    Then the service does not start
    And the service says why, naming <named>

    Examples:
      | problem                                          | named                                                       |
      | is not a module at all                           | that it is not a module                                     |
      | lacks a function the platform calls              | the function it lacks                                       |
      | was made for an ABI version the platform does not speak | the ABI version it was made for and the ABI version the platform speaks |

  Scenario: a module that declares a route answered as a stream is refused
    Given a service "shop" whose module declares an endpoint with a route answered as a stream
    When the platform's own program of "shop" starts
    Then the service does not start
    And the service says why, naming the route
