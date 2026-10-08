Feature: The platform's own tests need no cloud
  The platform's own tests run a scripted cloud provider, which fulfils every cloud request with
  made-up answers and made-up secrets and reaches no cloud. A cloud provider for a real cloud is
  tested with the same features against a real cloud account, on its own. A cloud provider for
  another cloud fulfils the same six cloud requests, and the platform learns only its name.

  Scenario: the scripted cloud provider fulfils every cloud request without a cloud
    Given the scripted cloud provider
    When the platform's own tests of cloud requests run
    Then they reach no cloud and need no credential of any cloud
    And every one of them passes

  Scenario: a cloud provider for a real cloud is tested with the same features against a real cloud account
    Given a cloud provider that fulfils cloud requests in a real cloud account
    When the same tests are run against it
    Then each passes as it passes against the scripted cloud provider
    And a cloud request the cloud provider fulfils differently from the scripted cloud provider fails there

  Scenario: a cloud provider for another cloud needs only its name known to the platform
    Given a cloud provider written for another cloud, which fulfils all six cloud requests
    When an installation names it as its cloud provider
    Then the operator writes the same six cloud requests and cannot tell which cloud provider answered
    And the platform changed only the names of the cloud providers it knows
