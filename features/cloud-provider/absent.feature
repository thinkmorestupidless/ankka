Feature: An installation with no cloud provider says so
  The operator cannot know that a cloud provider exists except by being answered. A cloud request
  nobody acknowledges within the acknowledgement bound is reported as waiting, naming the cloud
  provider that has not answered, and the report recovers when one answers. An installation whose
  cloud provider is "none" refuses, at the setting, anything only a cloud provider can give, and
  writes no cloud request at all.

  Scenario: a cloud request nobody acknowledges is reported after the acknowledgement bound
    Given an installation whose cloud provider is "gcp"
    And no cloud provider is running
    And a deployed service "reports" whose bucket request is not acknowledged
    When the acknowledgement bound passes
    Then the status of "reports" is "Waiting"
    And the status says that no cloud provider for "gcp" has answered

  Scenario: the status recovers when a cloud provider answers
    Given an installation whose cloud provider is "gcp"
    And a deployed service "reports" reported as "Waiting" because no cloud provider has answered
    When a cloud provider for "gcp" starts and fulfils the bucket request of "reports"
    Then the status of "reports" says what the cloud provider answered

  Scenario Outline: a setting that needs a cloud provider is refused when the installation has none
    Given an installation whose cloud provider is "none"
    When a member asks the control plane to <set>
    Then the member is refused
    And the refusal names the cloud provider needed
    And no cloud request is written

    Examples:
      | set                                               |
      | keep the project secrets of "shop" in the cloud account |
      | make the object store of "shop" the cloud account's     |
      | keep the backups of "shop" in the cloud account         |
      | wrap the keys of the keyring with a wrapping key        |

  Scenario: an installation with no cloud provider serves everything itself
    Given an installation whose cloud provider is "none"
    When every deployed service of the installation runs
    Then each database, each bucket, each project secret and the keys of the keyring are served by the installation itself, as before
    And no cloud request exists
