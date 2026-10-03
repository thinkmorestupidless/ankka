package com.thinkmorestupidless.ankka.sidecar

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/**
 * What a service logged while a suite ran, for a case that asserts a log line is written — the log
 * is the one place a deployed service's topic sources can be read today.
 */
final class LogLines:

  private val appender = ListAppender[ILoggingEvent]()

  private def root: Logger =
    LoggerFactory.getILoggerFactory
      .asInstanceOf[LoggerContext]
      .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

  def start(): Unit =
    appender.start()
    root.addAppender(appender)

  def stop(): Unit =
    root.detachAppender(appender): Unit
    appender.stop()

  /** Every line logged since `start`, formatted, at `level` or above. */
  def lines(level: String = "INFO"): Vector[String] =
    val threshold = ch.qos.logback.classic.Level.toLevel(level)
    appender.list.asScala.toVector
      .filter(_.getLevel.isGreaterOrEqual(threshold))
      .map(_.getFormattedMessage)

  def containing(fragment: String, level: String = "INFO"): Vector[String] =
    lines(level).filter(_.contains(fragment))
