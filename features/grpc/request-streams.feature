Feature: Calling a gRPC method with a stream
  A method may take a stream: its request arrives a part at a time. The handler reads each part as
  it is sent and no faster than it chooses, and may answer once or with a stream of its own.

  Scenario: a handler for a method that takes a stream reads each part as it is sent
    Given a gRPC endpoint whose handler for the method "ImportItems" takes a stream and answers once
    When a developer calls the method "ImportItems" and sends "3" parts
    Then the handler for the method "ImportItems" reads "3" parts
    And the call ends with the status "ok" and the handler's answer

  Scenario: a handler answers each part of a stream as it arrives
    Given a gRPC endpoint whose handler for the method "Converse" takes a stream and answers with a stream
    And the handler for the method "Converse" answers each part it reads with one part
    When a developer calls the method "Converse" and sends "1" part without ending the stream
    Then the developer is given "1" part

  Scenario: a stream is sent no faster than the handler reads it
    Given a gRPC endpoint whose handler for the method "ImportItems" takes a stream, reads "10" parts and then waits
    When a developer calls the method "ImportItems" and sends "100000" parts
    Then the service holds fewer than "1000" parts that the handler has not read

  Scenario: a handler is told when the developer who called goes away before ending the stream
    Given a gRPC endpoint whose handler for the method "ImportItems" takes a stream and answers once
    When a developer calls the method "ImportItems", sends "2" parts and goes away
    Then the handler for the method "ImportItems" reads "2" parts
    And the handler for the method "ImportItems" is told that the stream ended unfinished

  Scenario: a handler that refuses before the stream ends ends the call with that refusal's status
    Given a gRPC endpoint whose handler for the method "ImportItems" takes a stream and answers with the refusal "bad request" after "1" part
    When a developer calls the method "ImportItems" and sends "5" parts without ending the stream
    Then the call ends with the status "invalid argument"

  Scenario: a part the endpoint cannot read ends the call as an invalid argument
    Given a gRPC endpoint whose handler for the method "ImportItems" takes a stream and answers once
    When a developer calls the method "ImportItems" and sends "2" parts and then a part the gRPC endpoint cannot read
    Then the call ends with the status "invalid argument"
