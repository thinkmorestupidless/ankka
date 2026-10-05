Feature: Starting a service written in another language
  A service written in Python or TypeScript is a process run beside the platform's own program, and
  one written in Rust is a module the platform's own program loads. Either way the developer's code
  declares its components when the platform's own program starts, and the platform refuses to start
  a service it cannot host, naming every problem at once rather than the first.

  Scenario Outline: a service is not ready until its process has declared its components
    Given a service "shop" written in "<language>" whose process is not running
    When the platform's own program of "shop" starts
    Then "shop" is not ready
    And "shop" is ready once its process has started and declared its components

    Examples:
      | language   |
      | Python     |
      | TypeScript |

  Scenario Outline: a service whose code declares what the platform cannot host does not start
    Given a service "shop" written in "<language>" whose code declares <problem>
    When the service starts
    Then the service does not start
    And the service says why, naming <named>

    Examples:
      | language   | problem                                       | named                     |
      | Python     | two components named "cart"                   | the name "cart"           |
      | TypeScript | two components named "cart"                   | the name "cart"           |
      | Rust       | two components named "cart"                   | the name "cart"           |
      | Python     | a component of a kind the platform cannot host | the kind                 |
      | TypeScript | a component of a kind the platform cannot host | the kind                 |
      | Rust       | a component of a kind the platform cannot host | the kind                 |
      | Python     | two handlers of "cart" named "add-item"       | the handler "add-item"    |
      | TypeScript | two handlers of "cart" named "add-item"       | the handler "add-item"    |
      | Rust       | two handlers of "cart" named "add-item"       | the handler "add-item"    |
      | Python     | an endpoint with no ACL                       | the endpoint              |
      | TypeScript | an endpoint with no ACL                       | the endpoint              |
      | Rust       | an endpoint with no ACL                       | the endpoint              |

  Scenario Outline: a service whose code declares several problems is told every one at once
    Given a service "shop" written in "<language>" whose code declares two components named "cart" and an endpoint with no ACL
    When the service starts
    Then the service does not start
    And the service says why, naming the name "cart" and the endpoint

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a service whose code speaks a protocol version the platform does not is refused, naming both
    Given a service "shop" written in "<language>" whose code speaks the protocol version "9.0"
    When the service starts
    Then the service does not start
    And the service says why, naming the protocol version "9.0" and the protocol version the platform speaks

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
