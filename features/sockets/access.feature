Feature: Who may open a socket
  A socket route answers to its endpoint's ACL as any route does. The ACL is decided once, when
  the socket is opened: a request it does not admit opens no socket and runs no handler, and what
  it established is what the handler reads for as long as the socket is open.

  Background:
    Given an issuer "customers" that signs tokens for the audience "shop"
    And a service "notices" that lists the issuer "customers" with the audience "shop"
    And an HTTP endpoint of "notices" that declares the socket route "/stream"

  Scenario: the handler of an authenticated socket route is told the principal of whoever opened the socket
    Given the socket route "/stream" is an authenticated route
    When a person opens a socket to "/stream" with a token from "customers" whose subject is "ada"
    Then the handler is told a principal whose subject is "ada"

  Scenario: a browser opens a socket to an authenticated socket route with a token
    Given the socket route "/stream" is an authenticated route
    When a browser opens a socket to "/stream" with a token from "customers" whose subject is "ada"
    Then the handler is told a principal whose subject is "ada"
    And the token is not in what the service records of the socket

  Scenario: a handler reads the same principal for as long as the socket is open
    Given the socket route "/stream" is an authenticated route
    And a person has opened a socket to "/stream" with a token from "customers" whose subject is "ada"
    When the person sends "3" frames
    Then the handler reads a principal whose subject is "ada" after each frame

  Scenario: a socket stays open after the token it was opened with has expired
    Given the socket route "/stream" is an authenticated route
    And a person has opened a socket to "/stream" with a token from "customers" whose subject is "ada"
    When the token expires
    Then the socket is still open
    And the handler reads a principal whose subject is "ada"

  Scenario: a request to open a socket with no token is challenged as a request to any authenticated route is
    Given the socket route "/stream" is an authenticated route
    When a person opens a socket to "/stream" with no token
    Then the request is challenged
    And no socket is opened
    And the handler is not run

  Scenario: a request to open a socket that the ACL forbids is refused, and no handler runs
    Given the ACL of the socket route "/stream" is an authenticator
    And the authenticator answers "forbidden"
    When a person opens a socket to "/stream"
    Then the person is refused
    And no socket is opened
    And the handler is not run

  Scenario: a socket route that admits a named service tells its handler the calling workload
    Given the service "notices" is deployed
    And the ACL of the socket route "/stream" admits only the service "checkout"
    When the service "checkout" opens a socket to "/stream" of the service "notices"
    Then the handler reads the calling workload as the service "checkout"

  Scenario: a socket route that admits a named service refuses any other service
    Given the service "notices" is deployed
    And the ACL of the socket route "/stream" admits only the service "checkout"
    When the service "billing" opens a socket to "/stream" of the service "notices"
    Then the service "billing" is refused
    And no socket is opened
    And the handler is not run
