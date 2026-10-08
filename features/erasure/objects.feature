Feature: A data subject's objects are erased
  A service keeps the objects of one data subject under that data subject's subject prefix in its
  bucket, and its erasure handler asks the platform to erase them. Every version of each is
  deleted where the object store keeps versions, and the one version where it keeps one. The
  platform cannot see what a service writes to its bucket, so the erasure handler runs again on
  every later application and removes what was written under the subject prefix since.

  Background:
    Given a service "kyc" in the project "brand" with a bucket
    And "kyc" has an erasure handler that erases the objects of the data subject

  Scenario Outline: every object under a data subject's subject prefix is erased, and the rest are kept
    Given "kyc" has kept the objects "passport.jpg" and "proof-of-address.pdf" under the subject prefix of "player/8c1f"
    And "kyc" has kept the object "terms.pdf" outside any subject prefix
    And the object store of the installation keeps <versions>
    When "player/8c1f" is erased in "brand"
    Then the bucket of "kyc" holds no version of "passport.jpg" or of "proof-of-address.pdf" under the subject prefix of "player/8c1f"
    And the bucket of "kyc" still holds "terms.pdf"
    And the completion of "kyc" records that 2 objects were erased

    Examples:
      | versions                 |
      | every version of an object |
      | one version of an object |

  Scenario: the erasure request says when the erasure of objects becomes final on an object store with a soft-delete window
    Given the object store of the installation keeps every version of an object behind a soft-delete window
    And "kyc" has kept objects under the subject prefix of "player/8c1f"
    When "player/8c1f" is erased in "brand"
    Then the erasure request says that the erasure of objects becomes final when the soft-delete window has passed

  Scenario: an object written under the subject prefix after an erasure is removed on the next application
    Given "player/8c1f" has been erased in "brand"
    And "kyc" has since kept the object "late-scan.jpg" under the subject prefix of "player/8c1f"
    When the erasure request for "player/8c1f" is applied again
    Then the erasure handler of "kyc" ran again
    And the bucket of "kyc" holds no "late-scan.jpg" under the subject prefix of "player/8c1f"

  Scenario: a service with no bucket cannot erase objects
    Given a service "ledger" in the project "brand" whose descriptor asks for no bucket
    And "ledger" has an erasure handler that erases the objects of the data subject
    When "player/8c1f" is erased in "brand"
    Then the erasure handler of "ledger" is refused
    And the refusal names the missing bucket
    And the completion of "ledger" says so
