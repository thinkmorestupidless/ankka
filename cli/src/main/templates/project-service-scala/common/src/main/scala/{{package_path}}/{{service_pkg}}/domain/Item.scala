package {{package}}.{{service_pkg}}.domain

/**
 * The stub domain: an item with a name and a count. Replace it with {{service}}'s own; this file, the
 * entity that keeps it, the endpoint that exposes it and the view that lists it are the whole shape of
 * an ankka service.
 *
 * Plain data, no I/O. The entity decides what changes it; this file only says how a change is applied.
 */
final case class Item(id: String, name: String, count: Int):
  def onAdded(name: String, count: Int): Item =
    copy(name = name, count = this.count + count)

  def onRecorded(name: String, count: Int): Item = copy(name = name, count = count)

object Item:
  def empty(id: String): Item = Item(id, name = "", count = 0)

/** What has happened to an item. Events are the durable record; state is derived from them. */
enum ItemEvent:
  case ItemAdded(name: String, count: Int)

  /** The item is now what another service said it is. */
  case ItemRecorded(name: String, count: Int)
