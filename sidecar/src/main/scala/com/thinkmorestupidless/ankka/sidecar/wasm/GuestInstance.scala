package com.thinkmorestupidless.ankka.sidecar.wasm

import com.dylibso.chicory.runtime.{ImportValues, Instance}
import com.dylibso.chicory.wasm.types.MemoryLimits

import scala.util.control.NonFatal

/**
 * A call into a guest that did not return a reply: it trapped, or the host could not complete it.
 */
final case class GuestFault(function: String, message: String)

/**
 * One loaded copy of the module: its own linear memory, running one call at a time.
 *
 * A call follows the ABI's memory convention: the request is written into a buffer the guest
 * allocates and then owns; the reply comes back as a packed pointer and length, is read, and is
 * freed. A trap — or anything else that stops a call part-way — leaves the guest's memory in a
 * state nothing can vouch for, so the instance is marked broken and its pool replaces it.
 */
final class GuestInstance private (val instance: Instance):

  @volatile private var brokenBy: Option[GuestFault] = None

  def broken: Boolean                               = brokenBy.isDefined
  def fault: Option[GuestFault]                     = brokenBy
  private[wasm] def markBroken(f: GuestFault): Unit = brokenBy = Some(f)

  private lazy val alloc = instance.`export`(Abi.Prefix + "alloc")
  private lazy val free  = instance.`export`(Abi.Prefix + "free")

  /**
   * Calls `function` with `request`, and answers the reply's bytes (empty for a zero reply). The
   * call's `purpose` is held with the function's name as this thread's `CallSite` for as long as
   * the call lasts, which is what an import reads to know what it was called from. There is no way
   * to call without one: a call that stated none would be one the imports' rules could not see.
   */
  def call(
      function: String,
      request: Array[Byte],
      purpose: Purpose
  ): Either[GuestFault, Array[Byte]] =
    brokenBy match
      case Some(f) => Left(GuestFault(function, s"the instance is broken: ${f.message}"))
      case None =>
        try
          CallSite.within(CallSite(function, purpose, this)) {
            val ptr = alloc.apply(request.length.toLong)(0).toInt
            instance.memory().write(ptr, request)
            val out    = instance.`export`(function).apply(ptr.toLong, request.length.toLong)
            val packed = if out == null || out.isEmpty then 0L else out(0)
            if packed == 0L then Right(Array.emptyByteArray)
            else
              val (rptr, rlen) = (Abi.pointer(packed), Abi.length(packed))
              val reply        = instance.memory().readBytes(rptr, rlen)
              free.apply(rptr.toLong, rlen.toLong): Unit
              Right(reply)
          }
        catch
          case e: InterruptedException => throw e
          case e: StackOverflowError   => Left(breakWith(function, s"stack overflow: $e"))
          case NonFatal(e) =>
            Left(breakWith(function, Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.toString)))

  private def breakWith(function: String, message: String): GuestFault =
    val f = GuestFault(function, message)
    brokenBy = Some(f)
    f

object GuestInstance:

  /**
   * Builds one instance from the compiled module: the host's imports, the memory capped at
   * `maxMemoryPages` (never below what the module itself asks for at start), and the module's own
   * `_initialize` run once before anything else.
   */
  def build(module: LoadedModule, imports: ImportValues, maxMemoryPages: Int): GuestInstance =
    val builder = Instance
      .builder(module.module)
      .withImportValues(imports)
      .withMachineFactory(module.factory)
    val limited = initialPages(module).fold(builder) { initial =>
      builder.withMemoryLimits(MemoryLimits(initial, maxMemoryPages.max(initial)))
    }
    val instance = limited.build()
    if module.initialize then instance.`export`("_initialize").apply(): Unit
    GuestInstance(instance)

  private def initialPages(module: LoadedModule): Option[Int] =
    try
      val section = module.module.memorySection()
      if section.isPresent && section.get().memoryCount() > 0 then
        Some(section.get().getMemory(0).limits().initialPages())
      else None
    catch case NonFatal(_) => None
