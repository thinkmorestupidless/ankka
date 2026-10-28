Feature: The documentation of design and build
  A developer new to the platform learns from the documentation that a change is made by describing
  it, answering the design's clarifications, and approving the pull request the build raises.

  Scenario: the opening page points first to the page that presents the design and then the build
    Given the published documentation
    When a reader looks for how to build on the platform
    Then the opening page points first to a page that opens the getting started pages
    And that page presents the design and then the build as the way
    And that page comes before the page on skills and the MCP server

  Scenario: the page says what the design and the build produce and where the developer is asked
    Given the published documentation
    When a reader reads what the design and the build do
    Then the documentation says what the design produces and what the build produces
    And the documentation says where the developer is asked and where they are not
    And the documentation says that the two run on different models and why

  Scenario: the page says how design and build is installed and what the spec, the living features and the glossary are
    Given the published documentation
    When a reader reads how design and build is set up
    Then the documentation says how design and build is installed in a service made from the template and in any other repository
    And the documentation says what the change's directory, the living features and the glossary are

  Scenario: the page passes the documentation check and names the design and the build as a developer starts them
    Given the page on design and build
    When the documentation check runs
    Then the page passes
    And the names the page gives the design and the build are the names a developer starts them by
