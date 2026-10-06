package com.thinkmorestupidless.ankka.sidecar.wasm

import com.dylibso.chicory.compiler.MachineFactoryCompiler
import com.dylibso.chicory.runtime.{Instance, Machine}
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.{Parser, WasmModule}

import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/**
 * A module read, compiled and checked against the ABI once, at start; every instance is built from
 * it. `exports` are the names the module exports, which is how the host knows which optional
 * exports it can call.
 */
final case class LoadedModule(
    path: Path,
    module: WasmModule,
    factory: java.util.function.Function[Instance, Machine],
    initialize: Boolean,
    exports: Set[String]
):
  def exportsFunction(name: String): Boolean = exports.contains(name)

/**
 * Reads a module and refuses one the runtime cannot host, with every reason at once.
 *
 * What is checked here is what holds of any module: that it parses and compiles, that it speaks
 * this ABI's version and no other, that it has the exports every module needs, and that it imports
 * nothing but the `ankka1` functions. Which further exports it needs depends on what it declares,
 * and `WasmDiscovery` checks that once the declaration has been read.
 */
object ModuleLoader:

  /** Every export the host may call. */
  val Exports: Set[String] = Set(
    "alloc",
    "free",
    "discover",
    "handle",
    "fold",
    "run_step",
    "close",
    "view",
    "consumer",
    "timed_action",
    "plan",
    "invoke_tool",
    "check_guardrail",
    "check_task_result",
    "http"
  ).map(Abi.Prefix + _)

  /** Required of every module, whatever it declares. */
  val Required: Vector[String] = Vector("alloc", "free", "discover").map(Abi.Prefix + _)

  /**
   * The functions a module may import, all from the module named `ankka1`: exactly the ones
   * `HostImports.values` provides, which `WasmHostSuite` holds. The secret store's three arrived
   * with protocol 1.4; a module that does not use the store does not import them. `request`, `now`
   * and `random` arrived with 1.10, each imported only by a module that calls it.
   */
  val Imports: Set[String] =
    Set(
      "invoke",
      "send",
      "invoke_stream",
      "query",
      "schedule",
      "cancel",
      "config",
      "log",
      "get_secret",
      "put_secret",
      "delete_secret",
      "request",
      "now",
      "random"
    )

  private val Versioned = """ankka(\d+)_.*""".r

  def load(path: Path): Either[Vector[String], LoadedModule] =
    if !Files.isRegularFile(path) then Left(Vector(s"no module at $path"))
    else
      try
        val module = Parser.parse(path)
        check(module) match
          case problems if problems.nonEmpty => Left(problems)
          case _ =>
            compile(path, module).map { factory =>
              val exports = exportNames(module)
              LoadedModule(path, module, factory, exports.contains("_initialize"), exports)
            }
      catch
        case NonFatal(e) =>
          Left(
            Vector(
              s"$path is not a WebAssembly module: ${Option(e.getMessage).getOrElse(e.toString)}"
            )
          )

  /** Pure: what is wrong with a parsed module, all of it. */
  def check(module: WasmModule): Vector[String] =
    val problems = Vector.newBuilder[String]
    val exports  = exportNames(module)

    exports.toVector.sorted.foreach {
      case name @ Versioned(version) if version != Abi.Version =>
        problems += s"the module exports '$name', which is ABI version $version; this runtime " +
          s"speaks version ${Abi.Version} ('${Abi.Prefix}')"
      case _ => ()
    }
    Required.filterNot(exports.contains).foreach { name =>
      problems += s"the module does not export '$name', which every module must"
    }
    if !memoryExported(module) then problems += "the module does not export its memory as 'memory'"

    val imports = module.importSection()
    (0 until imports.importCount()).map(imports.getImport).foreach { i =>
      if i.module() != Abi.ImportModule then
        problems += s"the module imports '${i.module()}.${i.name()}'; a module may import only " +
          s"from '${Abi.ImportModule}'"
      else if i.importType() != ExternalType.FUNCTION || !Imports.contains(i.name()) then
        problems += s"the module imports '${i.module()}.${i.name()}', which the runtime does not " +
          s"provide (it provides ${Imports.toVector.sorted.mkString(", ")})"
    }
    problems.result()

  private def compile(
      path: Path,
      module: WasmModule
  ): Either[Vector[String], java.util.function.Function[Instance, Machine]] =
    try Right(MachineFactoryCompiler.compile(module))
    catch
      case NonFatal(e) =>
        Left(Vector(s"$path could not be compiled: ${Option(e.getMessage).getOrElse(e.toString)}"))

  private def exportNames(module: WasmModule): Set[String] =
    val section = module.exportSection()
    (0 until section.exportCount()).map(section.getExport(_).name()).toSet

  private def memoryExported(module: WasmModule): Boolean =
    val section = module.exportSection()
    (0 until section.exportCount())
      .map(section.getExport)
      .exists(e => e.name() == "memory" && e.exportType() == ExternalType.MEMORY)

/** The ABI's names, shared by the loader, the instances and the imports. */
object Abi:
  val Version: String      = "1"
  val Prefix: String       = s"ankka${Version}_"
  val ImportModule: String = s"ankka$Version"

  /** A reply's pointer and length in one `i64`, pointer high. */
  def pack(ptr: Int, len: Int): Long = (ptr.toLong << 32) | (len.toLong & 0xffffffffL)
  def pointer(packed: Long): Int     = (packed >>> 32).toInt
  def length(packed: Long): Int      = (packed & 0xffffffffL).toInt
