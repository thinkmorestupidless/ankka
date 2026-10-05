Feature: Installing the Rust SDK
  A Rust developer adds the SDK to a project from the package registry, at the version of the
  platform they deploy to, and builds a module from it with nothing else installed by hand.

  Scenario: the Rust SDK added from the package registry builds a module
    Given a project with nothing installed
    When the developer adds the SDK for "Rust" from the package registry
    Then the project builds a module
    And every package the SDK needs was added with it
