Feature: The documentation of the console
  A person learns from the documentation how to sign in to the console and what it does; a person
  installing the platform learns what the console needs from the installation and how to leave it
  out; a host's developer learns how to build on it. The limitations say what the console does not
  show.

  Scenario: the documentation's limitations describe the console rather than its absence
    Given the published documentation
    When a reader reads the limitations
    Then the documentation does not say that there is no console for a deployed installation
    And the documentation says what the console does not show

  Scenario: the documentation of the installation's issuer describes the console's place in it
    Given the published documentation
    When a reader reads about the installation's issuer
    Then the documentation describes the console's credential with the issuer and what it is
    And the documentation says how to add the console to an issuer set up before the console existed

  Scenario: the documentation of installing the platform names what the console needs and how to leave it out
    Given the published documentation
    When a reader reads about installing the platform in a cluster
    Then the documentation names the console's hostname, its credential with the issuer and its image among what a reader must set
    And the documentation says that leaving the console out is taking one line out of the installation

  Scenario: the documentation of the console can be found from its contents and shows only descriptors the platform accepts
    Given the published documentation
    When a reader looks for the documentation of the console
    Then every part of it can be reached from the documentation's contents
    And every part of it is among what an assistant is given about the platform
    And every descriptor it shows is one the platform accepts

  Scenario: the documentation tells a host's developer how to build on the console
    Given the published documentation
    When a reader reads about building a host on the console
    Then the documentation says how to serve the console under a path, what the host must give it, everything a host can add with what each is given, and which version of the console goes with which release
