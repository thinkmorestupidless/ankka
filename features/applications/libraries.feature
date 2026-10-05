Feature: Depending on the platform's libraries
  A service is built against the platform's libraries, published by name and version, and never
  against the platform's own source. Only what a service is built with is a library: the parts of
  the platform that run it are programs, and are not published as libraries.

  Scenario: a service builds against the platform's libraries published on the developer's machine
    Given the platform's libraries are published on a developer's machine at the version "0.12.0"
    When the developer builds a service that depends on the libraries at "0.12.0" and on nothing of the platform's own source
    Then the components of the service build against the libraries

  Scenario: the test kit starts a service on a database the libraries prepare
    Given a service that depends on the platform's libraries and holds nothing of the platform copied into it
    When a test of the service starts it with the test kit
    Then the test kit starts the service on a database prepared from what the libraries carry

  Scenario Outline: a part of the platform that runs services cannot be depended on as a library
    Given the platform's libraries are published on a developer's machine at the version "0.12.0"
    When the developer builds a service that depends on "<part>" at "0.12.0"
    Then the build fails because "<part>" is not published

    Examples:
      | part               |
      | ankka-controlplane |
      | ankka-operator     |
      | ankka-cli          |
      | ankka-crd          |

  Scenario: a release publishes every library at the release's version
    When a release of the platform at the version "0.12.0" is published
    Then every library is published at "0.12.0", signed, with its source and its documentation
