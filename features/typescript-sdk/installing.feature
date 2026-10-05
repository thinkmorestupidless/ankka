Feature: Installing the TypeScript SDK
  A Node developer adds the SDK to a project from the package registry and has everything it needs:
  its copy of the protocol comes with it, and nothing else has to be installed by hand.

  Scenario: the TypeScript SDK installed from the package registry carries everything it needs
    Given a project with nothing installed
    When the developer installs the SDK for "TypeScript" from the package registry
    Then the SDK can be imported
    And the SDK carries its own copy of the protocol
    And every package the SDK needs was installed with it
