Feature: A credential written once and read by nobody but the service
  The cloud provider makes a credential, a storage credential or the one a database reads its
  backups with, and writes it once into the secret the service's instances are given. Neither the
  operator nor the cloud provider reads it back afterwards, and neither can. A new credential is
  asked for by raising the credential generation; the old one goes on working until the rotation
  grace has passed, and is then ended.

  Scenario: a bucket credential request is fulfilled by offering the secret once
    Given a bucket credential request for "reports"
    And no secret "reports-storage" exists
    When the cloud provider fulfils it
    Then the cloud provider makes a storage credential that reaches the bucket of "reports" and no other
    And the cloud provider writes it into the secret "reports-storage"
    And the fulfilment says "Ready" and names the secret "reports-storage"

  Scenario: a bucket credential request whose secret is already there ends the credential just made
    Given a bucket credential request for "reports"
    And the secret "reports-storage" already exists
    When the cloud provider fulfils it
    Then the cloud provider ends the storage credential it had just made
    And the fulfilment says "Ready" and names the secret "reports-storage"
    And the storage credential of "reports" is the one it had before

  Scenario: the cloud provider can write a secret and cannot read one back
    Given a deployed service "reports" with a storage credential the cloud provider made
    When the cloud provider tries to read the secret "reports-storage"
    Then the cloud provider is refused
    And every cloud request is fulfilled with what the cloud provider may do

  Scenario: the operator cannot read a storage credential the cloud provider made
    Given a deployed service "reports" with a storage credential the cloud provider made
    When the operator tries to read the secret "reports-storage"
    Then the operator is refused

  Scenario: raising the credential generation replaces the credential and ends the old one after the rotation grace
    Given a deployed service "reports" with a storage credential at credential generation "1"
    When the credential generation of the bucket credential request of "reports" is raised to "2"
    Then the cloud provider makes a new storage credential and writes it into the secret "reports-storage"
    And the fulfilment says that credential generation "2" is in place
    And the instances of "reports" are replaced, and the new ones are given the new storage credential
    And the storage credential of credential generation "1" reaches the bucket until the rotation grace has passed since the fulfilment, and is refused by the bucket afterwards

  Scenario: a cloud request that goes with its service deletes nothing
    Given a deployed service "reports" with a bucket, a cloud identity and a storage credential the cloud provider made
    When a member deletes "reports"
    Then the cloud requests of "reports" go with it
    And the cloud account still holds the bucket, the cloud identity and the storage credential of "reports"
    And the secret "reports-storage" still exists
