# Your first service in Scala

> Create a Scala service from the ankka template, test it, run it against a local Postgres, watch it in the local console, and change its domain.

Source: https://docs.ankka.cloud/get-started/first-service-scala/
This tutorial creates a service from ankka's template, runs its tests, starts it on your machine, and
changes it. It takes about fifteen minutes once the tools are installed. You need the JDK, sbt, Docker
and the `ankka` CLI from [Install the tools](install.md).

## Create the service

`ankka init` expands the service template with `sbt new`:

```bash
ankka init orders
cd orders
```

`sbt new thinkmorestupidless/ankka.g8 --name=orders` does the same without the CLI. The name becomes
the project's directory, the image name, the descriptor's name and, once the service is exposed, part
of its hostname. It must be lowercase letters, digits and hyphens, starting with a letter, and at most
63 characters.

## What the template made

The stub domain is an item with a name and a count. Every file is named after your project, and the
shape is the shape of any ankka service:

```text
orders/
├── build.sbt                      ankka version, dependencies, image packaging, the `schema` task
├── docker-compose.yml             Postgres for running locally
├── service.json                   the descriptor `ankka services apply` takes
├── README.md                      the rest of the journey: image, deploy, expose, upgrade
└── src/
    ├── main/scala/<package>/
    │   ├── Main.scala             the whole service definition: what is registered
    │   ├── domain/Item.scala      plain data and rules, no ankka types
    │   ├── application/
    │   │   ├── ItemEntity.scala   an event sourced entity: one instance per item id
    │   │   └── ItemRows.scala     a view: one row per item, for listing
    │   └── api/ItemEndpoint.scala the HTTP routes
    └── test/scala/<package>/
        ├── ItemEntitySuite.scala        the entity with no runtime at all
        ├── ItemHttpSuite.scala          the routes over a real runtime and Postgres
        └── ItemIntegrationSuite.scala   the service end to end
```

`Main.scala` is the complete inventory. Registration is explicit, so a component you forget to
register fails at startup rather than at its first request:

```scala
@main def run(): Unit =
  val service = Ankka.service
    .register(ItemEntity.descriptor)
    .register(ItemRows.descriptor)
    .withExtension(ProjectionRuntime())
    .withExtension(HttpServer.of(clients => ItemEndpoint(clients.componentClient, clients.viewClient)))
    .start()

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
```

`ProjectionRuntime` is what runs views. Without it every listing stays empty while every write
succeeds.

The entity decides what happens to an item. Each handler returns an effect and performs no I/O:

```scala
final class ItemEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[Item, ItemEvent]:

  private val itemId: String = context.entityId

  def emptyState: Item = Item.empty(itemId)

  def applyEvent(event: ItemEvent): Item = event match
    case ItemAdded(name, count) => currentState.onAdded(name, count)

  def addItem(request: AddItem): Effect[Done] =
    if request.count <= 0 then
      effects.error(s"count must be greater than zero, was ${request.count}")
    else effects.persist(ItemAdded(request.name, request.count)).thenReply(_ => Done)

  def getItem: ReadOnlyEffect[Item] = effects.reply(currentState)

object ItemEntity
    extends EventSourcedEntity.Companion[ItemEntity, Item, ItemEvent](
      componentId = ComponentId("item"),
      stateSerializer = Codecs.serializer[Item]("item"),
      eventSerializer = Codecs.serializer[ItemEvent]("item-event")
    ):

  given Serializer[AddItem] = Codecs.serializer[AddItem]("add-item")

  def create(context: EventSourcedEntityContext) = new ItemEntity(context)

  val addItem = command("add-item")(_.addItem)
  val getItem = query("get-item")(_.getItem)
```

The strings `"add-item"` and `"get-item"` are wire names: the names the platform routes and stores by.
Rename the Scala methods freely; changing a wire name is a protocol change.
[Handlers and wire names](../concepts/wire-names.md) explains why.

## Run the tests

```bash
sbt test
```

`ItemEntitySuite` runs the entity with no actor system, cluster or database, so it takes milliseconds.
The other two suites start a throwaway Postgres in Docker and run the real runtime against it. The
first run downloads dependencies and the Postgres image, so it is slower than the ones after it.

## Run it

The service keeps its journal, view rows and timers in Postgres. The database schema comes out of the
`ankka-runtime` library, so extract it first and then start Postgres with it:

```bash
sbt schema                # writes ankka's schema into target/ddl
docker compose up -d      # Postgres on 5432, initialised from target/ddl
sbt run                   # the service, HTTP on :9000
```

In another terminal:

```bash
curl -XPOST localhost:9000/items/i1 -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
# (HTTP 204, no body)

curl localhost:9000/items/i1
# {"id":"i1","name":"Widget","count":2}

curl localhost:9000/items/
# [{"id":"i1","name":"Widget","count":2}]

curl -XPOST localhost:9000/items/i2 -H 'content-type: application/json' -d '{"name":"Widget","count":0}'
# 400: count must be greater than zero, was 0
```

Stop the service with ctrl-c and start it again: the item is still there. Its state was never stored,
only its events, and the entity rebuilt itself by replaying them.

Postgres applies its init directory only to an empty volume. After anything changes the schema, such
as an ankka upgrade, recreate it with `docker compose down -v`, `sbt schema` and `docker compose up -d`.

## Watch it in the local console

```bash
ankka local console       # http://localhost:9889
```

The console finds every ankka service running on your machine by itself. For this one it shows the
two registered components, a form for each HTTP route, and a trace of every request the service has
served. Send `POST /items/i1` again from the form and open its trace:

```text
POST /{id}                     41 ms
├── item#add-item              1.2 ms
└── unattributed               40 ms   (97%)
```

The entity took a millisecond. The unattributed time is what the platform could not assign to a
component, and here it is mostly the journal write. The console can also read an item's state, through
the entity's own declared query `get-item`. It refuses to run `add-item`, because a command can persist
and the console only runs queries. [The local console](../operate/local-console.md) covers the rest.

## Change the domain

Add a way to rename an item. The change touches each layer once, which is the usual shape of a change
to an ankka service.

In `domain/Item.scala`, a new event and the rule for applying it:

```scala
final case class Item(id: String, name: String, count: Int):
  def onAdded(name: String, count: Int): Item =
    copy(name = name, count = this.count + count)

  def onRenamed(name: String): Item = copy(name = name)

enum ItemEvent:
  case ItemAdded(name: String, count: Int)
  case Renamed(name: String)
```

In `application/ItemEntity.scala`, apply the event, add the handler and declare it under a wire name:

```scala
  def applyEvent(event: ItemEvent): Item = event match
    case ItemAdded(name, count) => currentState.onAdded(name, count)
    case Renamed(name)          => currentState.onRenamed(name)

  def rename(request: Rename): Effect[Done] =
    if request.name.isBlank then effects.error("name must not be blank")
    else effects.persist(Renamed(request.name)).thenReply(_ => Done)

final case class Rename(name: String)

object ItemEntity
    extends EventSourcedEntity.Companion[ItemEntity, Item, ItemEvent](
      componentId = ComponentId("item"),
      stateSerializer = Codecs.serializer[Item]("item"),
      eventSerializer = Codecs.serializer[ItemEvent]("item-event")
    ):

  given Serializer[AddItem] = Codecs.serializer[AddItem]("add-item")
  given Serializer[Rename]  = Codecs.serializer[Rename]("rename")

  def create(context: EventSourcedEntityContext) = new ItemEntity(context)

  val addItem = command("add-item")(_.addItem)
  val rename  = command("rename")(_.rename)
  val getItem = query("get-item")(_.getItem)
```

The `given` serializers are declared before the handlers because object initialisation runs in order,
and `command` needs the serializer for its argument.

In `application/ItemRows.scala`, keep the view's row in step. The compiler reports the missing case
if you forget this, because the match over `ItemEvent` is no longer exhaustive:

```scala
    event match
      case ItemAdded(name, count) =>
        effects.updateRow(current.copy(name = name, count = current.count + count))
      case Renamed(name) =>
        effects.updateRow(current.copy(name = name))
```

In `api/ItemEndpoint.scala`, a route. Add a codec for the body beside the others, and the route:

```scala
  private given JsonValueCodec[Rename] = Codecs.make[Rename]

  postBody("/{id}/name") { (id: String, request: Rename) =>
    item(id).call(ItemEntity.rename).invoke(request)
  }
```

Import `Rename` alongside `AddItem` at the top of the file. Then add a test to `ItemEntitySuite`:

```scala
  test("renaming records the new name") {
    val kit    = newKit
    val _      = kit.call(ItemEntity.addItem)(AddItem("Widget", 1))
    val result = kit.call(ItemEntity.rename)(Rename("Sprocket"))
    assertEquals(result.events, Vector(Renamed("Sprocket")))
    assertEquals(kit.currentState.name, "Sprocket")
  }
```

Run `sbt test`, restart the service, and rename the item:

```bash
curl -XPOST localhost:9000/items/i1/name -H 'content-type: application/json' -d '{"name":"Sprocket"}'
curl localhost:9000/items/i1
# {"id":"i1","name":"Sprocket","count":2}
```

The item's earlier events are still in the journal. Adding a new event type is always safe, because no
stored event has to change. [Serialization and evolution](../build/serialization.md) covers the
changes that are not.

## Next

[Deploy it to a local platform](deploy-locally.md): build its image, apply its descriptor, and reach it
over HTTPS.
