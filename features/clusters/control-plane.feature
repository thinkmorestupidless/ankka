Feature: A control plane of several instances
  The control plane runs as several instances on the same terms as any service. It does each piece
  of work once, not once for each instance, and keeps accepting members' requests while one of its
  instances is replaced.

  Background:
    Given a control plane running 3 instances

  Scenario: an applied descriptor is passed to the operator once, by one instance
    When a member applies a descriptor for the service "cart"
    Then the descriptor of "cart" is passed to the operator once, by one instance of the control plane

  Scenario: a report seen by every instance of the control plane is recorded once
    When every instance of the control plane sees the same report of "cart"
    Then the report is recorded once

  Scenario: members' requests sent while an instance of the control plane is replaced are all accepted
    Given members sending requests to the control plane one after another
    When an instance of the control plane is replaced
    Then every request is accepted and none is lost

  Scenario: when the instance passing descriptors to the operator stops, another takes over
    Given the instance of the control plane that passes descriptors to the operator
    When that instance stops
    Then another instance of the control plane passes descriptors to the operator
    And a descriptor applied afterwards reaches the operator
