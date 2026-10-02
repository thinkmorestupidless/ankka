Feature: Answering a gRPC call with a stream
  A handler may answer with a stream. Each part reaches whoever called as it is produced and no
  faster than it is read, and the stream stops being produced when nobody is reading it.

  Background:
    Given a gRPC endpoint whose handler for the method "WatchCart" answers with a stream

  Scenario: a method that answers with a stream delivers each part as it is produced
    Given the stream produces "3" parts, one each second
    When a developer calls the method "WatchCart"
    Then the developer is given each part as it is produced
    And the call ends with the status "ok" after the last part

  Scenario: a stream is produced no faster than it is read
    Given the stream produces "100000" parts
    When a developer calls the method "WatchCart" and reads "10" parts
    Then the stream has produced fewer than "1000" parts

  Scenario: a stream stops being produced when the developer who called goes away
    Given the stream produces parts and does not end
    When a developer calls the method "WatchCart" and goes away after "2" parts
    Then the stream stops being produced

  Scenario: a stream that ends in a refusal ends the call with that refusal's status
    Given the stream produces "2" parts and then ends in the refusal "unavailable"
    When a developer calls the method "WatchCart"
    Then the developer is given "2" parts
    And the call ends with the status "unavailable"

  Scenario: a call the ACL refuses ends before any part is sent
    Given the gRPC endpoint's ACL denies all
    When a developer calls the method "WatchCart"
    Then the call ends with the status "permission denied"
    And no part is sent
