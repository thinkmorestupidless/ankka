package {{package}}.contracts

import com.thinkmorestupidless.ankka.core.{Codecs, Contract, Serializer}

/**
 * The contract `{{first}}.v1`: what {{first}} publishes to the declared topic `{{first}}-events`, and
 * what {{second}} reads from it. One message per change, carrying the item as it now is.
 *
 * The contract's schema is `contracts/{{first}}.v1.json`, the file the context map names; the build
 * puts it on this module's classpath, so the fingerprint stated here is of that same file. A change a
 * reader could not read is a new contract, `{{first}}.v2`, beside this one.
 */
object {{First}}V1:

  final case class Item(id: String, name: String, count: Int)

  val serializer: Serializer[Item] = Codecs.serializer[Item]("{{first}}.v1")

  val contract: Contract =
    Contract.fromSchema("{{first}}.v1", schema("contracts/{{first}}.v1.json")) match
      case Right(contract) => contract
      case Left(why)       => throw IllegalStateException(s"contract {{first}}.v1: $why")

  private def schema(resource: String): Array[Byte] =
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(throw IllegalStateException(s"$resource is not on the classpath"))
    try stream.readAllBytes()
    finally stream.close()
