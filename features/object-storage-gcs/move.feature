Feature: A service's objects move from Garage to Google Cloud Storage
  An installation that kept its buckets in Garage and now keeps new ones in Google Cloud Storage
  keeps each existing bucket in Garage until a member moves it. A move copies every object into a
  bucket made for the service in Google Cloud Storage while the service goes on writing; then the
  service is given a read-only credential, its status says its storage is moving, what changed
  since the copy began is copied, every object is checked on both sides, and the service is given
  the variables of its new bucket when it is next restarted. The bucket in Garage is left as it
  was, because the platform deletes nothing.

  Background:
    Given an installation whose object store was Garage and is now Google Cloud Storage

  Scenario: a service's objects are moved from Garage to Google Cloud Storage
    Given a deployed service "kyc" with a bucket in Garage holding the objects "passport.pdf" and "selfie.jpg"
    When a member moves the storage of "kyc"
    Then the bucket made for "kyc" in Google Cloud Storage holds the objects "passport.pdf" and "selfie.jpg", each with the contents it had in Garage
    And the status says that the bucket of "kyc" is in Google Cloud Storage
    And the history of "kyc" says that a member moved its storage

  Scenario: a service reads its moved objects after its next rollout
    Given a deployed service "kyc" whose storage a member has moved to Google Cloud Storage
    When "kyc" is restarted
    Then every instance of "kyc" that started after the restart is given the variables of its bucket in Google Cloud Storage
    And "kyc" reads the object "passport.pdf" back from its bucket

  Scenario: a move that stopped part way is finished by running it again
    Given a move of the storage of "kyc" that stopped part way through copying
    When a member moves the storage of "kyc" again
    Then the move finishes
    And every object of "kyc" is in its bucket in Google Cloud Storage once, with the contents it had in Garage

  Scenario: a move that finds an object it cannot verify does not switch the service
    Given a move of the storage of "kyc" in which the object "passport.pdf" differs between Garage and Google Cloud Storage after copying
    When the move checks every object on both sides
    Then the move fails, naming the object "passport.pdf"
    And "kyc" keeps the variables of its bucket in Garage
    And "kyc" is given back a storage credential that writes

  Scenario: objects written during the bulk copy are copied in the write pause before the switch
    Given a move of the storage of "kyc" copying every object while "kyc" goes on writing
    And "kyc" keeps the object "selfie.jpg" in its bucket while the copy runs
    When the copy finishes
    Then the move gives "kyc" a read-only credential and copies the object "selfie.jpg"
    And the bucket of "kyc" in Google Cloud Storage holds the object "selfie.jpg" before "kyc" is given its variables

  Scenario: during the pause the service can read and not write, and its status says the storage is moving
    Given a move of the storage of "kyc" that has given "kyc" a read-only credential
    When "kyc" keeps the object "proof.pdf" in its bucket
    Then Garage refuses "kyc"
    And "kyc" still reads the object "passport.pdf" back from its bucket
    And the status says that the storage of "kyc" is moving, and since when

  Scenario: a move that fails during the pause gives the service its writes back on Garage
    Given a move of the storage of "kyc" that has given "kyc" a read-only credential
    When the move fails
    Then "kyc" is given a storage credential that writes to its bucket in Garage
    And the status says that the bucket of "kyc" is in Garage, and that the move failed

  Scenario: a service not yet moved keeps its bucket on Garage
    Given a deployed service "ledger" with a bucket in Garage, whose storage no member has moved
    When a member reads the status of "ledger"
    Then the status says that the bucket of "ledger" is in Garage
    And "ledger" is given the variables of its bucket in Garage

  Scenario: the Garage bucket is kept after a move
    Given a deployed service "kyc" whose storage a member has moved to Google Cloud Storage
    When a platform administrator reads the bucket of "kyc" in Garage
    Then Garage still holds the bucket of "kyc" with every object it had
