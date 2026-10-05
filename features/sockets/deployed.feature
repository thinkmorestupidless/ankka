Feature: A socket to a deployed service
  A socket to an exposed service is opened from the internet at the service's hostname, through
  the gateway, and is held open for as long as both sides want it. When the instance that holds
  it is replaced the socket is closed, never cut off, and one opened at once is served.

  Background:
    Given a deployed service "notices" with an HTTP endpoint that declares the socket route "/stream"
    And the ACL of the socket route "/stream" allows all

  Scenario: a socket to an exposed service is opened at its hostname, and its calling workload is the gateway
    Given the service "notices" is exposed
    And the handler of the socket route "/stream" sends one frame for each frame it reads
    When a browser opens a socket to "/stream" at the hostname of the service "notices" and sends "3" frames
    Then the handler reads the calling workload as the gateway
    And the handler reads the "3" frames in the order the browser sent them
    And the browser is given "3" frames in the order the handler sent them

  Scenario: a browser's token opens an authenticated socket route at an exposed service's hostname
    Given an issuer "customers" that signs tokens for the audience "shop"
    And the service "notices" lists the issuer "customers" with the audience "shop" and is exposed
    And the service "notices" declares the socket route "/account" as an authenticated route
    When a browser opens a socket to "/account" at the hostname of the service "notices" with a token from "customers" whose subject is "ada"
    Then the handler is told a principal whose subject is "ada"
    And the handler reads the calling workload as the gateway

  Scenario: a socket that no frame crosses for ten minutes is still open
    Given the service "notices" is exposed
    And a person has opened a socket to "/stream" at the hostname of the service "notices"
    When no frame crosses the socket for "10" minutes
    Then the socket is still open
    And a frame the person then sends reaches the handler

  Scenario: a socket on an instance that is replaced is closed, not cut off
    Given the service "notices" is exposed
    And a person has opened a socket to "/stream" at the hostname of the service "notices"
    When a member restarts the service "notices"
    Then the socket is closed with the close reason "going away"
    And the socket is not cut off

  Scenario: a socket opened while a service's instances are replaced is served by an instance that is ready
    Given the service "notices" is exposed
    And a person has opened a socket to "/stream" at the hostname of the service "notices"
    When a member restarts the service "notices" and the person opens a socket again once the first is closed
    Then the socket is opened by an instance that is ready

  Scenario: a socket route of a service that is not exposed opens no socket from the internet
    Given the service "notices" is not exposed
    When a person opens a socket to "/stream" of the service "notices" from the internet
    Then no socket is opened
    And the handler is not run

  Scenario: the topology of a service shows its socket routes
    Given the HTTP endpoint of the service "notices" declares the route "GET /notices"
    When a member reads the topology of "notices"
    Then the member is shown the socket route "/stream" beside the route "GET /notices"
