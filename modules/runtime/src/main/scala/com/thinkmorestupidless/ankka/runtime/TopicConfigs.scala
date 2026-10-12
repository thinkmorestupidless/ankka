package com.thinkmorestupidless.ankka.runtime

import org.apache.kafka.clients.admin.{Admin, AdminClientConfig}
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import org.slf4j.{Logger, LoggerFactory}

import java.util.Properties
import java.util.concurrent.{ConcurrentHashMap, ExecutionException, TimeUnit}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * What the broker says of a topic that the runtime acts on (feature 043): how it is cleaned, and
 * how many copies must hold a message before it is acknowledged.
 */
final case class TopicConfig(cleanup: Set[String], minInSync: Int):
  def compacted: Boolean = cleanup.contains("compact")

object TopicConfig:
  /** From a topic's `cleanup.policy` and `min.insync.replicas` as the broker writes them. */
  def of(cleanupPolicy: Option[String], minInsyncReplicas: Option[String]): TopicConfig =
    TopicConfig(
      cleanupPolicy.toVector.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty).toSet,
      minInsyncReplicas.flatMap(_.trim.toIntOption).getOrElse(1)
    )

/**
 * A publication to a compacted topic with no key to keep it under (feature 043): refused in the
 * service, before anything is sent, since the broker would refuse it and the change would be handed
 * to its consumer again with no word of why.
 */
final class KeylessPublication(topic: String)
    extends IllegalStateException(
      s"topic '$topic' is compacted and a message published to it must carry a key or a subject"
    )

/**
 * The broker's own configuration of each topic a service publishes to or reads, read when first
 * asked and again once a reading is older than `interval` (feature 043). Never from the project's
 * declarations or a variable: a topic's settings change on the broker in place, and a running
 * service must follow them with no restart. A reading that is stale is still answered while a new
 * one is fetched, so a publish waits only for the first; a read that fails keeps the last reading
 * and says so once.
 *
 * @param read
 *   one reading of a topic by the name the broker holds it under: `None` for a topic the broker
 *   does not know
 */
final class TopicConfigs(
    read: String => Option[TopicConfig],
    interval: FiniteDuration = TopicConfigs.Interval,
    now: () => Long = () => System.nanoTime(),
    onClose: () => Unit = () => ()
)(using ExecutionContext)
    extends AutoCloseable:

  def close(): Unit = onClose()

  private val log: Logger = LoggerFactory.getLogger("ankka.topics")

  private final case class Reading(config: Option[TopicConfig], at: Long)

  private val readings = ConcurrentHashMap[String, Reading]()
  private val pending  = ConcurrentHashMap[String, Future[Option[TopicConfig]]]()
  private val failing  = ConcurrentHashMap.newKeySet[String]()

  /**
   * The topic's configuration: the reading in hand when it is fresh; the reading in hand while a
   * new one is fetched when it is stale; and the first reading, once made, when there is none.
   */
  def get(topic: String): Future[Option[TopicConfig]] =
    Option(readings.get(topic)) match
      case Some(reading) =>
        if now() - reading.at > interval.toNanos then refresh(topic): Unit
        Future.successful(reading.config)
      case None => refresh(topic)

  /** A reading made now, whatever is held. */
  def describe(topic: String): Future[Option[TopicConfig]] = refresh(topic)

  private def refresh(topic: String): Future[Option[TopicConfig]] =
    pending.computeIfAbsent(
      topic,
      _ =>
        Future(blocking(read(topic)))
          .map { config =>
            readings.put(topic, Reading(config, now()))
            failing.remove(topic)
            config
          }
          .recover { case NonFatal(e) =>
            if failing.add(topic) then
              val cause = Option(e.getCause).fold("")(c => s" (${c.getMessage})")
              log.warn(
                "could not read the configuration of topic '{}' from the broker: {}{}",
                topic,
                e.getMessage,
                cause
              )
            Option(readings.get(topic)).flatMap(_.config)
          }
          .andThen(_ => pending.remove(topic))
    )

object TopicConfigs:

  /** How old a reading may be before it is fetched again. */
  val Interval: FiniteDuration = 60.seconds

  /**
   * Readings over one `Admin` client to `connection`'s broker, made on first use: a service that
   * never asks holds no connection for it.
   */
  def kafka(connection: KafkaConnection)(using ExecutionContext): TopicConfigs =
    val admin = KafkaTopicConfigReader(connection)
    new TopicConfigs(admin.read, onClose = () => admin.close())

/** One `describeConfigs` per reading, over a client made the first time one is asked for. */
private final class KafkaTopicConfigReader(connection: KafkaConnection) extends AutoCloseable:

  @volatile private var made: Option[Admin] = None

  private def admin: Admin =
    made.getOrElse(synchronized {
      made.getOrElse {
        val properties = Properties()
        connection.properties.foreach((key, value) => properties.put(key, value))
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, connection.bootstrapServers)
        // Both, or the client refuses to be made: the API timeout may not be under the request's.
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000")
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000")
        val created = Admin.create(properties)
        made = Some(created)
        created
      }
    })

  def read(topic: String): Option[TopicConfig] =
    val resource = ConfigResource(ConfigResource.Type.TOPIC, topic)
    try
      val config = admin
        .describeConfigs(java.util.List.of(resource))
        .all()
        .get(10, TimeUnit.SECONDS)
        .get(resource)
      val values = config.entries.asScala.map(e => e.name -> e.value).toMap
      Some(TopicConfig.of(values.get("cleanup.policy"), values.get("min.insync.replicas")))
    catch
      case e: ExecutionException if e.getCause.isInstanceOf[UnknownTopicOrPartitionException] =>
        None

  def close(): Unit = made.foreach(_.close())
