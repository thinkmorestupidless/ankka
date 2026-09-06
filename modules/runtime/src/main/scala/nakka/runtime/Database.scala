package nakka.runtime

import io.r2dbc.spi.{Connection, ConnectionFactory, Row, RowMetadata, Statement}

import java.util.function.BiFunction
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.r2dbc.ConnectionFactoryProvider
import org.apache.pekko.stream.scaladsl.{Sink, Source}

import scala.concurrent.{ExecutionContext, Future}

/**
 * A thin bridge from r2dbc's Reactive Streams API to Futures.
 *
 * Reuses the connection pool the persistence plugin already created rather than opening
 * a second one — a view and its source entity belong to the same database, and two pools
 * against one Postgres just doubles the connection count for no benefit.
 */
private[nakka] final class Database(factory: ConnectionFactory)(using system: ActorSystem[?]):

  private given ExecutionContext = system.executionContext

  /**
   * Runs `use` on a pooled connection, returning it whatever happens.
   *
   * `Sink.last`, not `Sink.head`: `head` cancels its upstream as soon as an element
   * arrives, and cancelling r2dbc's connection publisher mid-handover means the pool
   * never gets that connection back. A few queries then drain the pool and every
   * subsequent one hangs.
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

  /** Executes a statement, returning the number of rows affected. */
  def execute(fragment: SqlFragment): Future[Long] =
    withConnection { connection =>
      Source
        .fromPublisher(Database.bind(connection.createStatement(fragment.render), fragment).execute())
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

private[nakka] object Database:

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
