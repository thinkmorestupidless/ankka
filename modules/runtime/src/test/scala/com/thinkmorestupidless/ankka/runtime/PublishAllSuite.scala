package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata}
import com.thinkmorestupidless.ankka.runtime.ProjectionSupport.Encoded
import org.apache.pekko.Done

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.Try

/**
 * How the several messages of one change are published: the one function the in-process, sidecar
 * and module paths share. No actor system, no database, no broker.
 */
class PublishAllSuite extends munit.FunSuite:

  private val consumer = ComponentId("fanout")

  private def bytes(text: String)                 = text.getBytes("UTF-8")
  private def await[A](future: Future[A]): Try[A] = Try(Await.result(future, 5.seconds))

  private def publish(target: MessagePublisher, messages: Encoded*): Try[Done] =
    await(ProjectionSupport.publishAll(consumer, "cart-1", "lines", target, messages))

  test("messages are published in the order given, each under its key, else its subject") {
    val publisher = InMemoryPublisher()
    val result = publish(
      publisher,
      Encoded(bytes("a"), Metadata.empty, None),
      Encoded(bytes("b"), Metadata.empty, Some("line:2")),
      Encoded(bytes("c"), Metadata.empty.withSubject("other"), None),
      Encoded(bytes("d"), Metadata.empty.withSubject("other"), Some("line:4"))
    )
    assert(result.isSuccess, result.toString)
    val published = publisher.publishedTo("lines")
    assertEquals(published.map(_.text), Seq("a", "b", "c", "d"))
    // The subject defaults to the source's id and is left alone when the message sets it; naming
    // a key changes the key and nothing else.
    assertEquals(
      published.map(_.metadata.subject),
      Seq(Some("cart-1"), Some("cart-1"), Some("other"), Some("other"))
    )
    assertEquals(published.map(_.key), Seq(None, Some("line:2"), None, Some("line:4")))
    assertEquals(
      published.map(_.recordKey),
      Seq(Some("cart-1"), Some("line:2"), Some("other"), Some("line:4"))
    )
  }

  test("a message is sent only once the one before it is answered") {
    // A publisher that answers each message only when told to, as a broker client that hands a
    // record over on a thread of its own does. Sent all at once, the second message would go out
    // before the first was answered, and could reach its partition first.
    val answers = new java.util.concurrent.ConcurrentLinkedQueue[scala.concurrent.Promise[Done]]()
    val sent    = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val publisher = new MessagePublisher:
      def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done] =
        sent.add(String(payload, "UTF-8"))
        val answer = scala.concurrent.Promise[Done]()
        answers.add(answer)
        answer.future
    val result = ProjectionSupport.publishAll(
      consumer,
      "cart-1",
      "lines",
      publisher,
      Seq(Encoded(bytes("a"), Metadata.empty, None), Encoded(bytes("b"), Metadata.empty, None))
    )
    assertEquals(
      sent.toArray.toSeq,
      Seq[AnyRef]("a"),
      "the second was sent before the first was answered"
    )
    answers.poll().success(Done)
    assertEquals(sent.toArray.toSeq, Seq[AnyRef]("a", "b"))
    answers.poll().success(Done)
    assert(await(result).isSuccess)
  }

  test("other metadata travels with its own message only") {
    val publisher = InMemoryPublisher()
    val _ = publish(
      publisher,
      Encoded(bytes("a"), Metadata.empty, None),
      Encoded(bytes("b"), Metadata.empty.set("x-n", "2"), None)
    )
    assertEquals(publisher.published.map(_.metadata.get("x-n")), Seq(None, Some("2")))
  }

  test("no messages publish nothing and succeed") {
    val publisher = InMemoryPublisher()
    assert(publish(publisher).isSuccess)
    assertEquals(publisher.published, Seq.empty)
  }

  test("an empty key is refused, and nothing is published") {
    val publisher = InMemoryPublisher()
    val result = publish(
      publisher,
      Encoded(bytes("a"), Metadata.empty, None),
      Encoded(bytes("b"), Metadata.empty, Some(""))
    )
    val message = result.failed.get.getMessage
    assert(message.contains("'fanout'") && message.contains("empty record key"), message)
    assertEquals(publisher.published, Seq.empty)
  }

  test("a result over the limit is refused whole, naming the consumer, the subject and the limit") {
    val publisher = InMemoryPublisher()
    val megabyte  = Array.fill[Byte](1024 * 1024)('x'.toByte)
    val result    = publish(publisher, Seq.fill(5)(Encoded(megabyte, Metadata.empty, None))*)
    val message   = result.failed.get.getMessage
    assert(message.contains("'fanout'"), message)
    assert(message.contains("'cart-1'"), message)
    assert(message.contains("5 messages"), message)
    assert(message.contains(ProjectionSupport.MaxResultBytes.toString), message)
    assertEquals(publisher.published, Seq.empty)
    // Just under the limit goes through: the bound is on the whole, not on a count.
    assert(publish(publisher, Seq.fill(3)(Encoded(megabyte, Metadata.empty, None))*).isSuccess)
    assertEquals(publisher.published.size, 3)
  }

  test("when the broker refuses one message the result fails, and the others were still sent") {
    val broker = InMemoryBroker()
    broker.failNext("lines", after = 1)
    val result = publish(
      broker,
      Encoded(bytes("a"), Metadata.empty, None),
      Encoded(bytes("b"), Metadata.empty, None),
      Encoded(bytes("c"), Metadata.empty, None)
    )
    assert(result.failed.get.isInstanceOf[InMemoryBroker.Refused], result.toString)
    // Not atomic: what the broker accepted stays. The change comes again and all are sent again.
    assertEquals(broker.publishedTo("lines").map(_.text), Seq("a", "c"))
    val again = publish(
      broker,
      Encoded(bytes("a"), Metadata.empty, None),
      Encoded(bytes("b"), Metadata.empty, None),
      Encoded(bytes("c"), Metadata.empty, None)
    )
    assert(again.isSuccess, again.toString)
    assertEquals(broker.publishedTo("lines").map(_.text), Seq("a", "c", "a", "b", "c"))
  }

  test("a publisher that throws rather than failing its future fails the result the same way") {
    val throwing = new MessagePublisher:
      def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done] =
        throw IllegalStateException("the producer is closed")
    val result = publish(throwing, Encoded(bytes("a"), Metadata.empty, None))
    assertEquals(result.failed.get.getMessage, "the producer is closed")
  }

  test(
    "a publisher that was never taught keys publishes an un-keyed message and refuses a keyed one"
  ) {
    val seen = collection.mutable.Buffer[String]()
    val subjectOnly = new MessagePublisher:
      def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done] =
        seen += String(payload, "UTF-8")
        Future.successful(Done)
    assert(publish(subjectOnly, Encoded(bytes("a"), Metadata.empty, None)).isSuccess)
    assertEquals(seen.toSeq, Seq("a"))
    val refused = publish(subjectOnly, Encoded(bytes("b"), Metadata.empty, Some("line:2")))
    val message = refused.failed.get.getMessage
    assert(
      message.contains("line:2") && message.contains("keys every message by its subject"),
      message
    )
    // Published under the subject, the message would be taken for a different record.
    assertEquals(seen.toSeq, Seq("a"))
  }

  test(
    "the in-memory broker delivers a keyed message with that key, and its subject in the metadata"
  ) {
    val broker   = InMemoryBroker()
    val received = collection.mutable.Buffer[IncomingMessage]()
    broker.subscribe("lines", "reader", m => { received += m; Future.successful(Done) })
    val _ = publish(broker, Encoded(bytes("a"), Metadata.empty, Some("line:1")))
    assertEquals(received.map(_.key).toSeq, Seq(Some("line:1")))
    // A reader in an ankka service still sees the entity's id as the subject.
    assertEquals(received.map(_.subject).toSeq, Seq(Some("cart-1")))
  }
