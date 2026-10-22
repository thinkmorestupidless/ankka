import { test } from "node:test";
import assert from "node:assert/strict";
import { bucketRows } from "../src/ui/buckets.ts";
import { bucketReportSchema } from "../src/client/schemas.ts";

const report = (r: Record<string, unknown>) => bucketReportSchema.parse(r);

test("each named bucket is a row: its exposure, its store bucket, its address, and a kept one's note", () => {
  const rows = bucketRows([
    report({ name: "photos", phase: "Provisioned", store: "garage", bucket: "shop.listings.photos", exposure: "public", publicAddress: "https://storage.example.com/shop.listings.photos" }),
    report({ name: "exports", phase: "Provisioned", store: "garage", bucket: "shop.listings.exports", exposure: "signed", publicAddress: "https://storage.example.com/shop.listings.exports" }),
    report({ name: "documents", phase: "Provisioned", store: "garage", bucket: "shop.listings.documents" }),
    report({ name: "archive", phase: "Supplied", bucket: "acme-archive", exposure: "supplied" }),
    report({ name: "old-photos", phase: "Kept", store: "garage", bucket: "shop.listings.old-photos", exposure: "public", publicAddress: "https://storage.example.com/shop.listings.old-photos", kept: true }),
  ]);
  assert.deepEqual(
    rows.map((r) => [r.name, r.exposure, r.bucket, r.address, r.note]),
    [
      ["photos", "public", "shop.listings.photos", "https://storage.example.com/shop.listings.photos", null],
      ["exports", "signed", "shop.listings.exports", "https://storage.example.com/shop.listings.exports", null],
      ["documents", "private", "shop.listings.documents", null, null],
      ["archive", "supplied", "acme-archive", null, null],
      ["old-photos", "kept", "shop.listings.old-photos", null, "kept, no longer named"],
    ],
  );
});

test("a service with no named bucket has no rows", () => {
  assert.deepEqual(bucketRows(undefined), []);
  assert.deepEqual(bucketRows([]), []);
});
