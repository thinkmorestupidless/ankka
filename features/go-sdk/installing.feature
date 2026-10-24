Feature: Installing the Go SDK
  A Go developer adds the SDK to a project from the package registry, at the version of the platform
  they deploy to, and builds a service from it with nothing else installed by hand. The service is
  one executable, and its image holds that executable and no language runtime.

  Scenario: the Go SDK fetched from the package registry builds a service with nothing else added
    Given a project with nothing installed
    When the developer adds the SDK for "Go" from the package registry
    Then the project builds a service
    And the SDK carries its own copy of the protocol
    And every package the SDK needs was added with it

  Scenario: a service's image holds its one executable and no language runtime
    Given a service "shop" written in "Go"
    When the developer builds the image of "shop"
    Then the image holds the one executable of "shop"
    And the image holds no language runtime, no shell and no package manager
    And "shop" runs from the image beside the platform's own program
