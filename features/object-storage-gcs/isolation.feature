Feature: A storage credential in Google Cloud Storage reaches one bucket, and the operator touches no Google Cloud
  On an installation whose object store is Google Cloud Storage, each service with a bucket has a
  storage account of its own, granted on its bucket and on nothing else, and its storage credential
  belongs to that account. The provider makes the bucket, the account, the grant and the storage
  credential, reaching Google Cloud through its own workload identity; the operator asks it for the
  bucket and reads what it reports, holding no client, credential or permission of Google Cloud's.
  Neither can read a storage credential back. A member may have a storage credential issued again,
  and the old one is refused from then on.

  Background:
    Given an installation whose object store is Google Cloud Storage

  Scenario Outline: a storage credential is refused by another service's Google Cloud Storage bucket
    Given a deployed service "kyc" with a bucket in the project "casino"
    And a deployed service "ledger" with a bucket in the project "<project>"
    When "kyc" reads the bucket of "ledger" with its own storage credential
    Then Google Cloud Storage refuses "kyc"

    Examples:
      | project |
      | casino  |
      | bank    |

  Scenario: the operator holds no Google client, credential or permission and reads only the provider's status
    Given a descriptor for a service "kyc" that asks for a bucket
    And the operator of the installation
    When a member applies the descriptor
    Then the operator asks the provider for the bucket of "kyc" and reads what the provider reports
    And the operator holds no client, credential or permission of Google Cloud's, and has no identity in Google Cloud
    And the bucket of "kyc" is made

  Scenario: the provider reaches Google Cloud through its workload identity and holds no Google key
    Given the provider of the installation
    When the provider makes the bucket of "kyc"
    Then the provider reached Google Cloud as its workload identity
    And the provider holds no key of Google Cloud's in any form

  Scenario Outline: neither the operator nor the provider can read a storage credential back
    Given a deployed service "kyc" with a bucket
    When <who> tries to read the storage credential of "kyc"
    Then <who> is refused

    Examples:
      | who          |
      | the operator |
      | the provider |

  Scenario: a service's storage account is granted on its own bucket and on nothing else
    Given a deployed service "kyc" with a bucket in the project "casino"
    And a deployed service "ledger" with a bucket in the project "casino"
    When a platform administrator reads what the storage account of "kyc" is granted on in Google Cloud
    Then the storage account of "kyc" is granted on the bucket of "kyc" and on nothing else

  Scenario: a storage credential issued again is a new one, and the old one is refused
    Given a deployed service "kyc" with a bucket
    When a member asks for the storage credential of "kyc" to be issued again
    Then "kyc" reads its bucket with a storage credential that is not the one it had before, once it is restarted
    And Google Cloud Storage refuses the storage credential "kyc" had before
    And the history of "kyc" says that its storage credential was issued again
