Feature: A storage credential in Google Cloud Storage reaches one bucket, and the operator touches no Google Cloud
  On an installation whose object store is Google Cloud Storage, each service with a bucket has a
  cloud identity of its own, granted on its bucket and on nothing else, and its storage credential
  belongs to that account. The cloud provider makes the bucket, the account, the grant and the storage
  credential, reaching Google Cloud through its own workload identity; the operator asks it for the
  bucket and reads what it reports, holding no client, credential or permission of Google Cloud's.
  Neither can read a storage credential back. A member may have a storage credential issued again:
  the service is restarted onto the new one, and the old one is refused once the rotation grace
  has passed, whatever the restart did.

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

  Scenario: the operator holds no Google client, credential or permission and reads only the cloud provider's status
    Given a descriptor for a service "kyc" that asks for a bucket
    And the operator of the installation
    When a member applies the descriptor
    Then the operator asks the cloud provider for the bucket of "kyc" and reads what the cloud provider reports
    And the operator holds no client, credential or permission of Google Cloud's, and has no identity in Google Cloud
    And the bucket of "kyc" is made

  Scenario: the cloud provider reaches Google Cloud through its workload identity and holds no Google key
    Given the cloud provider of the installation
    When the cloud provider makes the bucket of "kyc"
    Then the cloud provider reached Google Cloud as its workload identity
    And the cloud provider holds no key of Google Cloud's in any form

  Scenario Outline: neither the operator nor the cloud provider can read a storage credential back
    Given a deployed service "kyc" with a bucket
    When <who> tries to read the storage credential of "kyc"
    Then <who> is refused

    Examples:
      | who          |
      | the operator |
      | the cloud provider |

  Scenario: a service's cloud identity is granted on its own bucket and on nothing else
    Given a deployed service "kyc" with a bucket in the project "casino"
    And a deployed service "ledger" with a bucket in the project "casino"
    When a platform administrator reads what the cloud identity of "kyc" is granted on in Google Cloud
    Then the cloud identity of "kyc" is granted on the bucket of "kyc" and on nothing else

  Scenario: a storage credential issued again is a new one, and the old one is refused once the rotation grace has passed
    Given a deployed service "kyc" with a bucket
    When a member asks for the storage credential of "kyc" to be issued again
    Then "kyc" is restarted once the fulfilment says the new storage credential is in place
    And "kyc" reads its bucket with a storage credential that is not the one it had before, once it is restarted
    And the storage credential "kyc" had before reaches its bucket until the rotation grace has passed since the fulfilment, and Google Cloud Storage refuses it afterwards
    And the history of "kyc" says that its storage credential was issued again

  Scenario: an instance not yet replaced when the rotation grace has passed is refused with the storage credential it was given
    Given a deployed service "kyc" with a bucket, whose storage credential a member has asked to be issued again
    And an instance of "kyc" that has not been replaced when the rotation grace has passed since the fulfilment
    When that instance reads the bucket of "kyc" with the storage credential it was given
    Then Google Cloud Storage refuses it
