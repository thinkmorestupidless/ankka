Feature: Sockets in a service's traces
  A socket is recorded as a request is: one root, from when it was opened until it was closed,
  marked by how it ended. What the handler caused while the socket was open is under that root,
  and frames are not recorded one by one.

  Background:
    Given a service "notices" with an HTTP endpoint that declares the socket route "/stream"
    And the ACL of the socket route "/stream" allows all

  Scenario: a socket is the root of the trace of everything its handler caused
    Given the handler of the socket route "/stream" calls a component after each frame it reads
    When a developer opens a socket to "/stream", sends "3" frames and closes the socket
    Then the service records a trace whose root is the socket to "/stream"
    And the trace shows "3" calls to the component under the root

  Scenario: a socket is one root in the trace however many frames cross it
    Given the handler of the socket route "/stream" sends one frame for each frame it reads
    When a developer opens a socket to "/stream", sends "10" frames and closes the socket
    Then the service records a trace with "1" root

  Scenario: a socket closed by whoever opened it is recorded as ok
    Given the handler of the socket route "/stream" finishes when it is told that the socket is closed
    When a developer opens a socket to "/stream" and closes the socket
    Then the service records a trace whose root is marked ok
