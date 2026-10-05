Feature: The documentation of socket routes
  A developer who cannot find how to declare a socket route has not been given one, and a developer
  who was not told what a socket does not do will find out from a deployed service.

  Scenario: the documentation describes a socket route in every language that has one
    Given the published documentation
    When a developer reads about HTTP endpoints
    Then the documentation describes how a socket route is declared in "Scala", in "Python" and in "TypeScript"
    And the documentation says that the ACL of a socket route is decided when the socket is opened
    And the documentation describes how a browser sends a token when it opens a socket

  Scenario: the documentation says what a socket does not do
    Given the published documentation
    When a developer reads about what a socket does not do
    Then the documentation says that a frame is text only
    And the documentation says that a module cannot declare a socket route
    And the documentation says that the platform keeps no record of who holds a socket open and keeps no frame
    And the documentation says that a socket route is not reached under a mount

  Scenario: the documentation states the limits of a socket
    Given the published documentation
    When a developer reads about the platform settings
    Then the documentation says how large a frame may be
    And the documentation says how many frames a socket holds unread
    And the documentation says how long the platform leaves a socket quiet

  Scenario: the documentation of how the platform talks to a process describes sockets
    Given the published documentation
    When a developer reads about how the platform talks to a process
    Then the documentation describes how a process is told of a socket and of each frame
    And the documentation says which protocol version first carried sockets
