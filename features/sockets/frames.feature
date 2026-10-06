Feature: A socket on an endpoint
  An HTTP endpoint may declare a socket route. A request to it opens a socket, over which whoever
  opened it and the handler send each other frames until one of them closes it. The handler runs
  for as long as the socket is open, and the platform does nothing with a socket but carry it.

  Background:
    Given a service "notices" with an HTTP endpoint that declares the socket route "/stream"
    And the ACL of the socket route "/stream" allows all

  Scenario: frames cross a socket both ways, each in the order it was sent
    Given the handler of the socket route "/stream" sends one frame for each frame it reads
    When a developer opens a socket to "/stream" and sends "10" frames
    Then the handler reads the "10" frames in the order the developer sent them
    And the developer is given "10" frames in the order the handler sent them

  Scenario: a handler reads what the request that opened the socket carried for as long as the socket is open
    Given the HTTP endpoint of the service "notices" declares the socket route "/rooms/{room}" with an ACL that allows all
    And a developer has opened a socket to "/rooms/lobby"
    When the developer sends "3" frames
    Then the handler reads the path's "room" as "lobby" after each frame

  Scenario: a handler waiting for a frame is told when whoever opened the socket closes it
    Given a developer has opened a socket to "/stream"
    And the handler of the socket route "/stream" is waiting for a frame
    When the developer closes the socket
    Then the handler is told that the socket is closed

  Scenario: a socket is closed when its handler finishes
    Given the handler of the socket route "/stream" finishes after it reads "1" frame
    When a developer opens a socket to "/stream" and sends "1" frame
    Then the socket is closed with the close reason "finished"
    And the socket is not cut off

  Scenario: a socket whose handler fails is closed as failed, and recorded as failed
    Given the handler of the socket route "/stream" fails after it reads "1" frame
    When a developer opens a socket to "/stream" and sends "1" frame
    Then the socket is closed with the close reason "failed"
    And the socket is not cut off
    And the service records a trace whose root is marked failed

  Scenario: a handler that sends a frame over a closed socket is told that the socket is closed
    Given a developer has opened a socket to "/stream" and closed it
    When the handler of the socket route "/stream" sends a frame
    Then the handler is told that the socket is closed
    And the frame is not ignored without the handler being told

  Scenario: a frame larger than a frame may be closes the socket
    Given a developer has opened a socket to "/stream"
    When the developer sends a frame larger than a frame may be
    Then the socket is closed with the close reason "too large"
    And the handler does not read the frame

  Scenario: a socket holds only so many frames its handler has not read
    Given the handler of the socket route "/stream" reads no frame
    When a developer opens a socket to "/stream" and sends more frames than a socket holds unread
    Then the socket is closed with the close reason "unread"

  Scenario: a frame that is not text closes the socket
    Given a developer has opened a socket to "/stream"
    When the developer sends a frame that is not text
    Then the socket is closed with the close reason "not text"
    And the handler does not read the frame

  Scenario: the platform keeps a quiet socket open, and neither side is given a frame for it
    Given a developer has opened a socket to "/stream"
    When no frame crosses the socket for longer than the platform leaves a socket quiet
    Then the socket is still open
    And the handler reads no frame
    And the developer is given no frame

  Scenario: a service with a socket route and a route on the same path does not start
    Given the HTTP endpoint of the service "notices" declares the route "GET /stream"
    When a developer starts the service "notices"
    Then the service "notices" does not start
    And the reason names the path "/stream"

  Scenario: a socket whose handler is waiting for a frame holds no thread
    Given the handler of the socket route "/stream" is waiting for a frame
    When developers open "1000" sockets to "/stream" and send no frame
    Then the service "notices" holds no more threads than it held with no socket open
