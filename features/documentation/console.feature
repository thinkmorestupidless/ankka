Feature: The documentation of the console
  A host that restyles the console reads which properties it may set and what each may not be
  set to, so the documentation lists them where it describes the console.

  Scenario: the documentation of the console says how a host restyles it
    Given the published documentation
    When a reader reads about the console
    Then the documentation lists the properties a host may set
    And the documentation says how bright the glow custom property may be set, and why
