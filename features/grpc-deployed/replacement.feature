Feature: Replacing and adding instances of a service that serves gRPC
  A service that calls another keeps its connection for a long time. Replacing the called service's
  instances must refuse no call, and instances added to it must come to answer calls.

  Background:
    Given a deployed service "cart" that serves gRPC
    And a deployed service "checkout" that calls the method "GetCart" of the service "cart" every second

  Scenario: no call is refused while a service's instances are replaced one at a time
    Given the service "cart" has "3" instances
    When a member deploys a new version of the service "cart"
    Then every call from the service "checkout" ends with the status "ok"

  Scenario: an instance added to a service comes to answer calls from a service that was already calling
    Given the service "cart" has "1" instance
    When a member gives the service "cart" "3" instances
    Then every instance of the service "cart" has answered a call from the service "checkout" within "5" minutes
    And no instance of the service "checkout" is restarted

  Scenario: no call from outside the cluster is refused while an exposed service's instances are replaced
    Given the service "cart" is exposed and has "3" instances
    And a developer calls the method "GetCart" at the hostname of the service "cart" every second
    When a member deploys a new version of the service "cart"
    Then every call from the developer ends with the status "ok"

  Scenario: a stream on an instance that is stopping is given time to finish, then ends as unavailable
    Given the service "checkout" has called a method of the service "cart" that answers with a stream that does not end
    When the instance of the service "cart" that answers the call is stopped
    Then the stream goes on for the time a stopping instance gives a call to finish
    And the call ends with the status "unavailable"
