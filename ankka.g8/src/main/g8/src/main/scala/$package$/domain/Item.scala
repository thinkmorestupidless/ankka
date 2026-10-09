package $package$.domain

import com.thinkmorestupidless.ankka.core.personal.Personal

/**
 * The stub domain: an item with a name and a count. Replace it with yours — this file, the entity
 * that keeps it, the endpoint that exposes it and the view that lists it are the whole shape of a
 * ankka service, and they are all in this project.
 *
 * Plain data, no I/O. The entity below decides what changes it; this file only says how a change is
 * applied — which is what makes both testable without a runtime.
 */
final case class Item(id: String, name: String, count: Int, owner: Option[Personal[String]] = None):
  def onAdded(name: String, count: Int): Item =
    copy(name = name, count = this.count + count)

  def onOwnerSet(owner: Personal[String]): Item = copy(owner = Some(owner))

object Item:
  def empty(id: String): Item = Item(id, name = "", count = 0)

/** What has happened to an item. Events are the durable record; state is derived from them. */
enum ItemEvent:
  case ItemAdded(name: String, count: Int)

  /**
   * The owner's email: a *personal field*, written encrypted under its data subject's key — the
   * owner, `user/<their id>` — and read as erased everywhere once that subject is erased. Add new
   * cases at the end: the order is the stored format.
   */
  case OwnerSet(owner: Personal[String])
