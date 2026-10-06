Feature: A socket route in every language
  A socket route works the same whether the service is written in Scala, Python or TypeScript,
  because the platform's own program holds the socket, decides the ACL and passes each frame on.
  A module cannot hold a socket, so a module that declares a socket route does not start.

  Scenario Outline: frames cross a socket both ways in every language
    Given a service "notices" written in "<language>" that declares the socket route "/stream"
    And the handler of the socket route "/stream" sends one frame for each frame it reads
    When a developer opens a socket to "/stream" and sends "10" frames
    Then the handler reads the "10" frames in the order the developer sent them
    And the developer is given "10" frames in the order the handler sent them

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario Outline: the handler of an authenticated socket route is told the principal in every language
    Given an issuer "customers" that signs tokens for the audience "shop"
    And a service "notices" written in "<language>" that lists the issuer "customers" with the audience "shop"
    And the service "notices" declares the socket route "/stream" as an authenticated route
    When a person opens a socket to "/stream" with a token from "customers" whose subject is "ada"
    Then the handler is told a principal whose subject is "ada"
    And the handler is told the calling workload

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario Outline: a request to open a socket with no token is challenged in every language
    Given an issuer "customers" that signs tokens for the audience "shop"
    And a service "notices" written in "<language>" that lists the issuer "customers" with the audience "shop"
    And the service "notices" declares the socket route "/stream" as an authenticated route
    When a person opens a socket to "/stream" with no token
    Then the request is challenged
    And no socket is opened
    And the handler is not run

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario Outline: a socket is closed when its handler finishes in every language
    Given a service "notices" written in "<language>" that declares the socket route "/stream"
    And the handler of the socket route "/stream" finishes after it reads "1" frame
    When a developer opens a socket to "/stream" and sends "1" frame
    Then the socket is closed with the close reason "finished"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario: the sockets of a process that stops are closed as failed
    Given a service "notices" hosted as a process that declares the socket route "/stream"
    And a developer has opened a socket to "/stream"
    When the process of the service "notices" stops
    Then the socket is closed with the close reason "failed"
    And the socket is not cut off

  Scenario: a socket is opened again once a process that stopped is running again
    Given a service "notices" hosted as a process that declares the socket route "/stream"
    And the process of the service "notices" has stopped and is running again
    When a developer opens a socket to "/stream" and sends "1" frame
    Then the handler reads the "1" frame

  Scenario Outline: a service does not declare a socket route to a platform older than socket routes
    Given a service "notices" written in "<language>" that declares the socket route "/stream"
    When "notices" starts on a platform whose protocol version is older than socket routes
    Then "notices" does not start
    And the reason names the protocol version

    Examples:
      | language   |
      | Python     |
      | TypeScript |

  Scenario: a service made for a protocol version older than socket routes is hosted as it was
    Given a service "notices" hosted as a process for a protocol version older than socket routes
    And the service "notices" declares no socket route
    When "notices" starts
    Then the routes of "notices" are served

  Scenario: a service that declares a socket route for a protocol version older than socket routes does not start
    Given a service "notices" hosted as a process for a protocol version older than socket routes
    And the service "notices" declares the socket route "/stream"
    When "notices" starts
    Then "notices" does not start
    And the reason names the protocol version

  Scenario: a module that declares a socket route does not start
    Given a service "notices" written in "Rust" that declares the socket route "/stream"
    When "notices" starts
    Then "notices" does not start
    And the reason names the socket route "/stream"
    And the reason says that a module cannot hold a socket
