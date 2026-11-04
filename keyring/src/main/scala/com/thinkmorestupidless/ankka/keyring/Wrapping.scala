package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.PersonalCipher

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}

/**
 * Keys wrapped twice (feature 042, R11): each project's key-encryption key wrapped by the root key,
 * and every subject key and the project's lookup key wrapped by the KEK. The associated data binds
 * each wrapped key to what it is the key of, so a row copied to another subject or project does not
 * unwrap. KEKs are held in memory once unwrapped; subject keys never are.
 */
final class Wrapping(store: KeyStore, root: RootKey)(using ExecutionContext):

  private val random = SecureRandom()
  private val keks   = ConcurrentHashMap[String, IArray[Byte]]()

  private def fresh(): Array[Byte] =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    bytes

  private def kekOf(project: String): Future[IArray[Byte]] =
    Option(keks.get(project)) match
      case Some(kek) => Future.successful(kek)
      case None      =>
        val ad = Wrapping.ad("kek", project, "")
        store.kek(project, root.ref)(root.wrap(fresh(), ad)).map { wrapped =>
          val kek = IArray.unsafeFromArray(root.unwrap(wrapped, ad))
          keks.putIfAbsent(project, kek)
          kek
        }

  /** `subject`'s key in the clear, made when absent and `create`; or why there is none. */
  def subjectKey(project: String, subject: String, create: Boolean): Future[Wrapping.Answer] =
    kekOf(project).flatMap { kek =>
      val ad = Wrapping.ad("subject", project, subject)
      store.subjectKey(project, subject, create)(PersonalCipher.seal(kek, None, fresh(), ad)).map {
        case KeyStore.Stored.Key(wrapped) =>
          Wrapping.Answer.Key(
            IArray.unsafeFromArray(
              PersonalCipher
                .open(kek, wrapped, ad)
                .getOrElse(
                  throw IllegalStateException(s"the key of $subject in $project did not unwrap")
                )
            )
          )
        case KeyStore.Stored.Destroyed => Wrapping.Answer.Destroyed
        case KeyStore.Stored.Absent    => Wrapping.Answer.Absent
      }
    }

  /** `project`'s lookup key in the clear, made at its first ask. */
  def lookupKey(project: String): Future[IArray[Byte]] =
    kekOf(project).flatMap { kek =>
      val ad = Wrapping.ad("lookup", project, "")
      store
        .lookupKey(project)(PersonalCipher.seal(kek, None, fresh(), ad))
        .map(wrapped =>
          IArray.unsafeFromArray(
            PersonalCipher
              .open(kek, wrapped, ad)
              .getOrElse(throw IllegalStateException(s"the lookup key of $project did not unwrap"))
          )
        )
    }

object Wrapping:

  enum Answer:
    case Key(key: IArray[Byte])
    case Destroyed
    case Absent

  /** What a wrapped key is the key of, length-prefixed as the envelope's associated data is. */
  def ad(kind: String, project: String, subject: String): Array[Byte] =
    PersonalCipher.associatedData(s"ankka-keyring/$kind/$subject", project, "")
