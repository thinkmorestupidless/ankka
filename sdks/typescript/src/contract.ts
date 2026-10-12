// A contract: a name and the fingerprint of the schema document a component was built against.

import { createHash } from "node:crypto"
import { readFileSync } from "node:fs"
import * as canonicalizeModule from "canonicalize"

// `canonicalize` is CommonJS whose declaration says `export default`: under Node's ESM loader the
// namespace's `default` is `module.exports`, the function, which is also what the types say.
const canonicalize = (canonicalizeModule as unknown as { default: (input: unknown) => string | undefined }).default

/** A contract's name: a topic's letters and `_`, 2 to 100 characters. */
export const CONTRACT_NAME_RULE = /^[a-z0-9][a-z0-9._-]{0,98}[a-z0-9]$/

/**
 * What a topic carries, as a project declares it and a component states it: a name and the
 * fingerprint of the schema document. The platform refuses, at a service's start, a side whose name
 * or fingerprint is not the declared one; the name travels as every message's `ce-type`. Nothing
 * checks a message against the schema.
 *
 * The fingerprint is `sha256:` and the hex SHA-256 of the document under RFC 8785 (the JSON
 * Canonicalization Scheme), so whitespace and key order in a saved file do not change it and a
 * changed field does. Every SDK fingerprints the same way, held to `protocol/fixtures/contracts/`.
 */
export class Contract {
  readonly name: string
  readonly fingerprint: string

  constructor(name: string, fingerprint: string) {
    if (!CONTRACT_NAME_RULE.test(name)) throw new TypeError(`contract name ${JSON.stringify(name)} is not ${CONTRACT_NAME_RULE.source}`)
    this.name = name
    this.fingerprint = fingerprint
  }

  /** The contract `name` of the schema document at `path`, fetched from the project's declaration. */
  static fromFile(path: string, name: string): Contract {
    return Contract.fromBytes(readFileSync(path), name)
  }

  /** The contract `name` of a schema document held in memory. */
  static fromBytes(document: Uint8Array | string, name: string): Contract {
    return new Contract(name, fingerprintOf(document))
  }

  /** @internal */
  equals(other: Contract): boolean {
    return this.name === other.name && this.fingerprint === other.fingerprint
  }
}

/** `sha256:` and the hex SHA-256 of `document` canonicalised under RFC 8785. Throws when it is not JSON. */
export function fingerprintOf(document: Uint8Array | string): string {
  const text = typeof document === "string" ? document : new TextDecoder("utf-8", { fatal: true }).decode(document)
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch (e) {
    throw new TypeError(`a schema document must be JSON: ${(e as Error).message}`)
  }
  const canonical = canonicalize(parsed)
  if (canonical === undefined) throw new TypeError("a schema document must be JSON")
  return "sha256:" + createHash("sha256").update(canonical, "utf8").digest("hex")
}

/**
 * A topic a consumer publishes to, with the contract it states for it and the declared broker it is
 * on. `producesTo` takes a plain topic name as the short form.
 */
export interface Publication {
  readonly topic: string
  readonly contract?: Contract
  readonly broker?: string
  /** Another project's topic (1.15), which that project must grant this service produce on. */
  readonly project?: string
}

/** The first protocol in which a process can read or publish to another project's topic. */
export const GRANTS_PROTOCOL: readonly [number, number] = [1, 15]

/** The first protocol in which a process can declare a contract, a broker or parallel reading. */
export const CONTRACT_PROTOCOL: readonly [number, number] = [1, 14]
