Feature: The documentation of web hosting
  A developer who has services on the platform and an interface for them learns from the
  documentation how to deploy the one beside the others, what the process is told, and what web
  hosting does not do.

  Scenario: the documentation takes a developer from nothing written to a deployed interface
    Given the published documentation
    When a reader with nothing written does what the documentation of web hosting says
    Then the reader has a web-hosted service deployed to a local platform
    And a browser is shown its interface

  Scenario: the documentation of the descriptor describes a web-hosted service
    Given the published documentation
    When a reader reads about the descriptor
    Then the documentation describes web hosting and mounts
    And the documentation says every refusal a descriptor for a web-hosted service can be given

  Scenario: the documentation says what the process is told and how it calls services
    Given the published documentation
    When a reader reads about web hosting
    Then the documentation says how the process is told who sent a request and where it was sent
    And the documentation describes the calling address

  Scenario: the documentation says that a mounted service has to admit the internet
    Given the published documentation
    When a reader reads about mounts
    Then the documentation says that a mounted service is told that a request under a mount came from the internet
    And the documentation says that a mounted service has to admit the internet
    And the documentation says that only a call the process makes is told to come from the web-hosted service

  Scenario: the documentation says what web hosting does not do
    Given the published documentation
    When a reader reads about what the platform does not do
    Then the documentation says that web hosting keeps no image but the one its descriptor names
    And the documentation says that a request may reach any instance of a web-hosted service
