Feature: The console installed with the platform
  The console is installed with the rest of the platform, at its own hostname under the
  installation's, and keeps nothing of its own. It proves its identity to the control plane, is
  reached only through the gateway, holds no credential for the cluster, and is published with each
  release. A developer runs it on their own machine against an issuer and a control plane there.

  Scenario: installing the platform on a developer's machine tells the developer the console's address
    Given a cluster on the developer's machine
    When the developer installs a local platform in it
    Then the developer is told the console's address
    And a browser that trusts the installation's certificates is asked to sign in at that address

  Scenario: the console calls the control plane as itself and checks whom it called
    Given a deployed console
    When the console calls the control plane
    Then the call proves the console's identity with the certificate the installation issued the console
    And the console checks that it was the control plane that answered

  Scenario: the gateway reaches the console over a connection it checks and nobody else can read
    Given a deployed console
    When the gateway sends the console a request
    Then the connection is made with the console's certificate, which the gateway checks
    And nobody else can read the connection

  Scenario: no workload but the gateway can connect to the console
    Given a deployed console
    When a workload of another project connects to the console
    Then the connection is refused

  Scenario: the console holds no credential the cluster accepts
    Given a deployed console
    When the console's own credential is used to ask the cluster for anything
    Then the cluster refuses every request
    And the console can reach only the control plane and the installation's issuer

  Scenario: a release publishes the console's image and the console for hosts at the release's version
    When a release is published
    Then the console's image is published with the platform's other images, at the release's version, for anyone to fetch
    And the installation's clusters can pull the console's image from where they pull the others
    And the console is published for hosts to build on, at the release's version

  Scenario: a developer runs the console on their own machine with no certificate
    Given an issuer and a control plane running on the developer's machine, as the documentation says
    When the developer starts the console as the documentation says
    Then the developer signs in as "dev", the person the documentation names
    And the developer is shown the organizations the control plane holds, with no certificate to set up

  Scenario: the example installation for a cloud asks for the console's settings and keeps no credential from development
    Given the example installation for a cluster in a cloud
    When a person reads what they must set before installing it
    Then the console's image, its hostname and its credential with the installation's issuer are among them
    And the console's credential from development is removed from it rather than replaced
