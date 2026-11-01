package {{package}}

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ContextMap}
import com.thinkmorestupidless.ankka.testkit.ContextMapCheck

import java.nio.file.{Files, Path}

/**
 * The context map, held to the code: every topic a Scala service of the project publishes to or
 * reads, and the contract it states, is a crossing the map records, and every topic crossing the map
 * records is stated by a component of that service. `ankka context-map check` holds the map to the
 * project's files; this holds it to what the services do.
 *
 * Each Scala service is found as `ankka init` writes it: the object `<Name>Service` in the package
 * `{{package}}.<name>`, where `<name>` is the service's name without its `-` and `<Name>` the same
 * with each word capitalised. A service added to the project is checked here with nothing edited.
 */
class ContextMapSuite extends munit.FunSuite:

  private val root = Path.of(sys.props.getOrElse("ankka.project.root", ".."))

  private val map: ContextMap = ContextMap.read(root) match
    case Right(map)     => map
    case Left(problems) => throw IllegalStateException(problems.mkString("context-map.json: ", "; ", ""))

  private def isScala(service: String): Boolean =
    val dir = root.resolve("services").resolve(service)
    Files.isDirectory(dir.resolve("src/main/scala")) && !Files.exists(dir.resolve("build.sbt"))

  private def descriptorsOf(service: String): Seq[ComponentDescriptor] =
    val pkg    = service.replace("-", "")
    val name   = service.split('-').map(_.capitalize).mkString
    val module = Class.forName(s"{{package}}.$pkg.${name}Service$$")
    val value  = module.getField("MODULE$").get(null)
    module.getMethod("descriptors").invoke(value).asInstanceOf[Seq[ComponentDescriptor]]

  for service <- map.services.map(_.name) if isScala(service) do
    test(s"$service states only the crossings the context map records") {
      assertEquals(ContextMapCheck.problems(map, service, descriptorsOf(service)), Vector.empty)
    }
