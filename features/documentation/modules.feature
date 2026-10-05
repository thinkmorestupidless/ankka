Feature: What the documentation says a module may ask the platform for
  A module reaches nothing but the platform, so what the platform offers it is the whole of what
  it can do. The documentation lists it where a developer writing a module will look, says which
  handlers may call another service, and keeps saying that a module cannot be interrupted.

  Scenario: the documentation lists what a module may ask the platform for and which handlers may call another service
    When a reader looks up modules in the documentation
    Then the documentation describes calling another service, reading the time and asking for random bytes
    And the documentation lists the handlers a module may call another service from

  Scenario: the documentation of the Rust SDK says where a module's time comes from
    When a reader looks up the time in the documentation of the SDK for "Rust"
    Then the documentation says a module reads the platform's time
    And the documentation says a test run outside a module reads the time of the machine it runs on

  Scenario: the documentation's limitations say a module cannot be interrupted and not that it cannot read the time
    When a reader looks up modules in the limitations of the documentation
    Then the documentation says a module cannot be interrupted
    And the documentation says a module's call to another service is not ended before the service called answers or the wait for it ends
    And the documentation does not say a module cannot read the time
