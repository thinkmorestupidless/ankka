Feature: The documentation of calling another service
  A developer learns from one page how a service calls another as itself, in every language and
  from every component that may, and the documentation no longer says that a service in some
  languages cannot.

  Scenario: one page shows a call to another service in every language
    Given the published documentation
    When a reader reads about calling another service
    Then the documentation shows the call in "Scala", "Python" and "TypeScript", from an endpoint and from a workflow's step
    And every call shown there is taken from code a test runs

  Scenario: the documentation does not say that a service in some languages cannot call another as itself
    Given the published documentation
    When a reader reads what the platform does not do
    Then the documentation does not say that a service in "Python" or "TypeScript" cannot call another service as itself

  Scenario: every page about calling another service is listed and carried by a skill
    Given the published documentation
    When a reader reads the list of its pages
    Then every page about calling another service is listed
    And every page about calling another service is in at least one skill
