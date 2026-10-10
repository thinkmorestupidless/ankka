package com.thinkmorestupidless.ankka.runtime

import io.r2dbc.spi.{Connection, ConnectionFactory, Row, RowMetadata, Statement}

import java.util.function.BiFunction
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.r2dbc.ConnectionFactoryProvider
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/**
 * A thin bridge from r2dbc's Reactive Streams API to Futures.
 *
 * Reuses the connection pool the persistence plugin already created rather than opening a second
 * one — a view and its source entity belong to the same database, and two pools against one
 * Postgres just doubles the connection count for no benefit.
 */
private[ankka] final class Database(factory: ConnectionFactory)(using system: ActorSystem[?]):

  private given ExecutionContext = system.executionContext

  /**
   * Runs `use` on a pooled connection, returning it whatever happens.
   *
   * `Sink.last`, not `Sink.head`: `head` cancels its upstream as soon as an element arrives, and
   * cancelling r2dbc's connection publisher mid-handover means the pool never gets that connection
   * back. A few queries then drain the pool and every subsequent one hangs.
   */
  def withConnection[A](use: Connection => Future[A]): Future[A] =
    Source
      .fromPublisher(factory.create())
      .runWith(Sink.last)
      .flatMap { connection =>
        use(connection).transformWith { outcome =>
          Source
            .fromPublisher(connection.close())
            .runWith(Sink.ignore)
            .transform(_ => outcome)
        }
      }

  /**
   * `executeAll`, inside one transaction: all or nothing, and anything the statements lock is
   * released on either outcome. What `ProjectionRuntime` uses to create view tables under a
   * transaction-scoped advisory lock — session-scoped would outlive a pooled connection.
   */
  def executeAllInTransaction(fragments: Seq[SqlFragment]): Future[Unit] =
    withConnection { connection =>
      def run(publisher: org.reactivestreams.Publisher[Void]): Future[Unit] =
        Source.fromPublisher(publisher).runWith(Sink.ignore).map(_ => ())
      run(connection.beginTransaction()).flatMap { _ =>
        fragments
          .foldLeft(Future.successful(())) { (previous, fragment) =>
            previous.flatMap { _ =>
              Source
                .fromPublisher(
                  Database.bind(connection.createStatement(fragment.render), fragment).execute()
                )
                .flatMapConcat(result => Source.fromPublisher(result.getRowsUpdated))
                .runWith(Sink.ignore)
                .map(_ => ())
            }
          }
          .transformWith {
            case scala.util.Success(_) => run(connection.commitTransaction())
            case scala.util.Failure(e) =>
              run(connection.rollbackTransaction()).transform(_ => scala.util.Failure(e))
          }
      }
    }

  /**
   * `work`, inside one transaction on one connection: committed when it succeeds, rolled back when
   * it fails, and anything it locked is released either way. For work whose next statement depends
   * on what an earlier one found, which `executeAllInTransaction` cannot express.
   */
  def inTransaction[A](work: Database.Transaction => Future[A]): Future[A] =
    withConnection { connection =>
      def run(publisher: org.reactivestreams.Publisher[Void]): Future[Unit] =
        Source.fromPublisher(publisher).runWith(Sink.ignore).map(_ => ())
      run(connection.beginTransaction()).flatMap { _ =>
        work(Database.Transaction(connection)).transformWith {
          case scala.util.Success(value) => run(connection.commitTransaction()).map(_ => value)
          case scala.util.Failure(e) =>
            run(connection.rollbackTransaction()).transform(_ => scala.util.Failure(e))
        }
      }
    }

  /**
   * `work`, inside a transaction the database holds to reading, whose every statement it ends once
   * `timeout` has passed. What a view's declared query runs in: the check at startup decides what a
   * statement says, and only the database can promise what it does and that it stops.
   */
  def readOnly[A](timeout: FiniteDuration)(work: Database.Transaction => Future[A]): Future[A] =
    inTransaction { tx =>
      tx.execute(SqlFragment.raw("SET TRANSACTION READ ONLY"))
        .flatMap(_ =>
          tx.execute(SqlFragment.raw(s"SET LOCAL statement_timeout = ${timeout.toMillis.max(1L)}"))
        )
        .flatMap(_ => work(tx))
    }

  /**
   * The rows of `sql`, which holds `$1`, `$2`, … for `binds` in order, as the database yields them:
   * nothing is collected, and the stream holds at most `fetchSize` rows the database has sent and
   * the reader has not taken.
   *
   * The statement runs in a transaction held to reading on a connection of its own for as long as
   * the stream lasts, and the connection goes back to the pool however the stream ends: completed,
   * failed, or cancelled by a reader that went away.
   *
   * The database's `statement_timeout` is measured per `Execute`, and a portal fetched `fetchSize`
   * rows at a time is one `Execute` per fetch: it bounds each fetch, not the stream, so a stream
   * may rightly run longer than `timeout`. What it cannot bound is a reader that stops reading and
   * holds the cursor open, so the stream bounds that itself: a stream not read for `timeout` fails.
   */
  def stream[A](timeout: FiniteDuration, fetchSize: Int)(sql: String, binds: Seq[Any])(
      decode: (Row, RowMetadata) => A
  ): Source[A, NotUsed] =
    def run(publisher: org.reactivestreams.Publisher[?]): Future[Unit] =
      Source.fromPublisher(publisher).runWith(Sink.ignore).map(_ => ())
    def statement(connection: Connection, text: String): Future[Unit] =
      Source
        .fromPublisher(connection.createStatement(text).execute())
        .flatMapConcat(result => Source.fromPublisher(result.getRowsUpdated))
        .runWith(Sink.ignore)
        .map(_ => ())
    // Read-only, so rolling back gives up nothing, and it is the one ending right for all three.
    def release(connection: Connection): Future[Unit] =
      run(connection.rollbackTransaction())
        .recover { case _ => () }
        .flatMap(_ => run(connection.close()))
        .recover { case _ => () }

    Source
      .lazyFutureSource { () =>
        Source.fromPublisher(factory.create()).runWith(Sink.last).flatMap { connection =>
          run(connection.beginTransaction())
            .flatMap(_ => statement(connection, "SET TRANSACTION READ ONLY"))
            .flatMap(_ =>
              statement(connection, s"SET LOCAL statement_timeout = ${timeout.toMillis.max(1L)}")
            )
            .map { _ =>
              val prepared = connection.createStatement(sql).fetchSize(fetchSize)
              binds.zipWithIndex
                .foreach((value, index) => prepared.bind(index, value.asInstanceOf[AnyRef]): Unit)
              val mapper: BiFunction[Row, RowMetadata, A] = (row, metadata) => decode(row, metadata)
              Source
                .fromPublisher(prepared.execute())
                .flatMapConcat(result => Source.fromPublisher(result.map(mapper)))
                .watchTermination()((_, ended) => ended.onComplete(_ => release(connection)))
            }
            .recoverWith { case failure =>
              release(connection).flatMap(_ => Future.failed(failure))
            }
        }
      }
      .backpressureTimeout(timeout)
      .mapError { case _: java.util.concurrent.TimeoutException =>
        Database.NotRead(timeout)
      }
      .mapMaterializedValue(_ => NotUsed)

  /** Executes a statement, returning the number of rows affected. */
  def execute(fragment: SqlFragment): Future[Long] =
    withConnection { connection =>
      Source
        .fromPublisher(
          Database.bind(connection.createStatement(fragment.render), fragment).execute()
        )
        .flatMapConcat(result => Source.fromPublisher(result.getRowsUpdated))
        .runWith(Sink.fold(0L)((total, updated) => total + updated.longValue))
    }

  /** Executes statements that return nothing, in order, on one connection. */
  def executeAll(fragments: Seq[SqlFragment]): Future[Unit] =
    withConnection { connection =>
      fragments.foldLeft(Future.successful(())) { (previous, fragment) =>
        previous.flatMap { _ =>
          Source
            .fromPublisher(
              Database.bind(connection.createStatement(fragment.render), fragment).execute()
            )
            .flatMapConcat(result => Source.fromPublisher(result.getRowsUpdated))
            .runWith(Sink.ignore)
            .map(_ => ())
        }
      }
    }

  /** Runs a query and decodes every row. */
  def query[A](fragment: SqlFragment)(decode: Row => A): Future[Vector[A]] =
    withConnection { connection =>
      val statement = Database.bind(connection.createStatement(fragment.render), fragment)
      Source
        .fromPublisher(statement.execute())
        .flatMapConcat { result =>
          // The BiFunction overload, explicitly: `Result.map` is also defined for
          // `Function[Readable, T]`, and an unannotated lambda picks that one.
          val mapper: BiFunction[Row, RowMetadata, A] = (row, _) => decode(row)
          Source.fromPublisher(result.map(mapper))
        }
        .runWith(Sink.seq)
        .map(_.toVector)
    }

  /** Runs a query expecting at most one row. */
  def queryOne[A](fragment: SqlFragment)(decode: Row => A): Future[Option[A]] =
    query(fragment)(decode).map(_.headOption)

private[ankka] object Database:

  /** A stream whose reader took nothing for `timeout`, so the stream gave up its cursor. */
  final case class NotRead(timeout: FiniteDuration)
      extends RuntimeException(s"the stream was not read for $timeout")

  /** The statements of one transaction, on its connection, one after another. */
  final class Transaction private[runtime] (connection: Connection)(using Materializer):

    /** Executes a statement, returning the number of rows affected. */
    def execute(fragment: SqlFragment): Future[Long] =
      Source
        .fromPublisher(bind(connection.createStatement(fragment.render), fragment).execute())
        .flatMapConcat(result => Source.fromPublisher(result.getRowsUpdated))
        .runWith(Sink.fold(0L)((total, updated) => total + updated.longValue))

    /** Runs a query and decodes every row. */
    def query[A](fragment: SqlFragment)(decode: Row => A): Future[Vector[A]] =
      val mapper: BiFunction[Row, RowMetadata, A] = (row, _) => decode(row)
      Source
        .fromPublisher(bind(connection.createStatement(fragment.render), fragment).execute())
        .flatMapConcat(result => Source.fromPublisher(result.map(mapper)))
        .runWith(Sink.seq)
        .map(_.toVector)(using ExecutionContext.parasitic)

    /**
     * Runs `sql`, which holds `$1`, `$2`, … for `binds` in order, and decodes at most `limit` rows.
     * The statement is sent as written; the rows past the limit are not read.
     */
    def queryUpTo[A](sql: String, binds: Vector[String], limit: Int)(
        decode: (Row, RowMetadata) => A
    ): Future[Vector[A]] =
      // The fetch size makes the database stop at the limit, rather than the driver reading and
      // discarding what it went on producing: a result that never ends is not read at all.
      val statement = connection.createStatement(sql).fetchSize(limit)
      binds.zipWithIndex.foreach((value, index) => statement.bind(index, value): Unit)
      val mapper: BiFunction[Row, RowMetadata, A] = (row, metadata) => decode(row, metadata)
      Source
        .fromPublisher(statement.execute())
        .flatMapConcat(result => Source.fromPublisher(result.map(mapper)))
        .take(limit.toLong)
        .runWith(Sink.seq)
        .map(_.toVector)(using ExecutionContext.parasitic)

  def apply()(using system: ActorSystem[?]): Database =
    val factory = ConnectionFactoryProvider
      .get(system)
      .connectionFactoryFor("pekko.persistence.r2dbc.connection-factory")
    new Database(factory)

  /** Binds a fragment's parameters positionally. r2dbc indexes from zero. */
  def bind(statement: Statement, fragment: SqlFragment): Statement =
    fragment.params.zipWithIndex.foreach { (param, index) =>
      statement.bind(index, param.value): Unit
    }
    statement
