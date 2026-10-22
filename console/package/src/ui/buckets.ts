import type { BucketReport } from "../client/schemas.ts";

/** One row of a service's named buckets, as the service page shows it (feature 055). */
export interface BucketRow {
  name: string;
  /** The exposure, `supplied` for a bucket the developer made, or `kept` for one no longer named. */
  exposure: string;
  /** Its name in the store, when reported. */
  bucket: string | null;
  /** Where it is on the internet, as a link, when it is. */
  address: string | null;
  /** Said of a bucket the descriptor no longer names, which is kept rather than deleted. */
  note: string | null;
}

export function bucketRows(buckets: readonly BucketReport[] | null | undefined): BucketRow[] {
  return (buckets ?? []).map((b) => ({
    name: b.name,
    exposure: b.kept ? "kept" : b.phase === "Supplied" || b.exposure === "supplied" ? "supplied" : b.exposure,
    bucket: b.bucket ?? null,
    address: b.kept ? null : (b.publicAddress ?? null),
    note: b.kept ? "kept, no longer named" : null,
  }));
}
