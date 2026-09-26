// `npm run conformance`: serve the TypeScript reference service and run the platform's conformance
// suite against it, exiting with sbt's status. Needs Docker (the suite starts Postgres) and sbt on PATH.
// `ANKKA_CONFORMANCE_ONLY` narrows the run to matching behaviours; `ANKKA_BENCHMARKS` turns the
// suite's benchmarks on.

import { spawn } from "node:child_process"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { referenceService } from "../examples/shopping-cart/conformance.ts"

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..")
const port = Number(process.env.ANKKA_PROCESS_PORT ?? 9010)

export async function main(): Promise<number> {
  const server = referenceService().server({ host: "127.0.0.1", port })
  await server.start()
  console.error(`conformance: reference service listening on 127.0.0.1:${port}`)
  const only = process.env.ANKKA_CONFORMANCE_ONLY
  const args = [
    `-Dankka.conformance.target=127.0.0.1:${port}`,
    ...(process.env.ANKKA_BENCHMARKS ? ["-Dankka.benchmarks=on"] : []),
    "-Dankka.cluster.tests=off",
    "-Dankka.template.tests=off",
    `sidecar/testOnly *ConformanceSuite${only ? ` -- ${only}` : ""}`,
  ]
  console.error(`running: sbt ${args.map((a) => (a.includes(" ") ? JSON.stringify(a) : a)).join(" ")} in ${repo}`)
  try {
    return await new Promise<number>((resolveExit) => {
      const sbt = spawn("sbt", args, { cwd: repo, stdio: "inherit" })
      sbt.on("exit", (code) => resolveExit(code ?? 1))
      sbt.on("error", (e) => {
        console.error(`could not run sbt: ${e.message}`)
        resolveExit(1)
      })
    })
  } finally {
    await server.stop()
  }
}

process.exit(await main())
