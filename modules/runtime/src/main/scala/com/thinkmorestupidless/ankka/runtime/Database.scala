package com.thinkmorestupidless.ankka.runtime

import io.r2dbc.spi.{Connection, ConnectionFactory, Row, RowMetadata, Statement}

import java.util.function.BiFunction
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
      if param.value == null then statement.bindNull(index, param.javaType): Unit
      else statement.bind(index, param.value): Unit
    }
    statement
