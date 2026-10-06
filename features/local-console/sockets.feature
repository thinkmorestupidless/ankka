Feature: Socket routes in the local console
  The local console lists what a service on a developer's machine serves. It lists a socket route
  beside the other routes of its endpoint, marked as a socket route.

  Scenario: the local console lists a service's socket routes beside its routes
    Given a service "notices" running on a developer's machine with an HTTP endpoint that declares the socket route "/stream" and the route "GET /notices"
    When a developer reads the service "notices" in the local console
    Then the local console lists the socket route "/stream" beside the route "GET /notices"
