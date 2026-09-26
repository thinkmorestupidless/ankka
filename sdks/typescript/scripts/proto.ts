// Copy the platform's protocol artifact in and regenerate the TypeScript stubs.
//
// `protocol/` at the repository root is the artifact: the `.proto` files, `ENCODING.md` and the
// fixtures. This copies it verbatim into `sdks/typescript/proto/` (committed, so CI can diff the two),
// generates `src/_proto/` (ignored) with `buf generate`, and writes `src/version.ts` from
// `package.json` so the version the SDK reports in discovery is the package's.

import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { dirname, join, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { spawnSync } from "node:child_process"

const sdk = resolve(dirname(fileURLToPath(import.meta.url)), "..")
const repo = resolve(sdk, "..", "..")
const source = join(repo, "protocol")
const copy = join(sdk, "proto")
const out = join(sdk, "src", "_proto")

function main(): number {
  if (existsSync(join(source, "src", "main", "protobuf"))) {
    // In the repository: refresh the committed copy from the artifact.
    rmSync(copy, { recursive: true, force: true })
    for (const sub of ["src/main/protobuf", "fixtures"]) {
      if (existsSync(join(source, sub))) cpSync(join(source, sub), join(copy, sub), { recursive: true })
    }
    for (const name of ["ENCODING.md", "README.md"]) {
      if (existsSync(join(source, name))) cpSync(join(source, name), join(copy, name))
    }
  } else if (existsSync(join(copy, "src", "main", "protobuf"))) {
    // Outside the repository (a Docker build, a checkout of the SDK alone): generate from the committed copy.
    console.error(`no protocol artifact at ${source}; generating from the copy in ${copy}`)
  } else {
    console.error(`no protocol artifact at ${source} and no copy at ${copy}`)
    return 1
  }
  rmSync(out, { recursive: true, force: true })
  mkdirSync(out, { recursive: true })

  const buf = spawnSync("npx", ["buf", "generate"], { cwd: sdk, stdio: "inherit" })
  if (buf.status !== 0) return buf.status ?? 1

  const pkg = JSON.parse(readFileSync(join(sdk, "package.json"), "utf8")) as { version: string }
  writeFileSync(
    join(sdk, "src", "version.ts"),
    `// Written by scripts/proto.ts from package.json; do not edit. 0.0.0 in the tree, the tag's version in a release.\nexport const VERSION = ${JSON.stringify(pkg.version)}\n`,
  )
  console.log(`generated into ${out.replace(sdk + "/", "")}; VERSION = ${pkg.version}`)
  return 0
}

process.exit(main())
