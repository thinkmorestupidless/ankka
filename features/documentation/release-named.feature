Feature: The documentation names the release it was built from
  Each release publishes its documentation, and beside it which release is the latest, once its
  libraries can be fetched. A page that names a release of ankka names the one it was built for,
  so the next release cannot leave it behind.

  Scenario: a published release is the latest release beside its documentation
    When release "0.17.0" is published
    Then the latest release beside the documentation is "0.17.0" with the date it was released

  Scenario: a release whose libraries were not published leaves the latest release where it was
    Given the latest release "0.16.0"
    When release "0.17.0" ends without its libraries reaching the package registry
    Then the latest release beside the documentation is "0.16.0"

  Scenario: a page that names a release of ankka names the release the documentation was built for
    When the documentation is built for release "0.16.0"
    And a reader looks up adding the SDK for "Go" in the documentation
    Then the documentation names release "0.16.0"

  Scenario: a page that names a release of ankka by hand fails the documentation's check
    Given a page that names the release "0.14.0" of ankka by hand
    When the documentation is checked
    Then the check fails and names the page and the line

  Scenario: a page that names a release of another project is not refused
    Given a page that names the release "0.14.1" of another project
    When the documentation is checked
    Then the check passes
