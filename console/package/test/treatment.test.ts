/**
 * features/console/accessibility.feature, "a browser that cannot blur what is behind a surface
 * still shows readable text": without backdrop-filter a translucent surface would show the glow
 * through it unblurred, so every surface the contrast test reasons about is drawn opaque over the
 * mesh's base instead. A browser cannot be made to lack the feature in the browser suite, so this
 * reads the stylesheet.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");

test("a browser that cannot blur what is behind a surface still shows readable text", () => {
  const at = css.indexOf("@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px)))");
  assert.ok(at >= 0, "the stylesheet has no fallback for a browser without backdrop-filter");
  const block = css.slice(at, css.indexOf("\n  }\n", at));
  for (const surface of [".ac-rail", ".ac-bar", ".ac-listing", ".ac-inspector", ".ac-card", ".ac-panel", ".ac-part"]) {
    assert.ok(block.includes(surface), `${surface} has no opaque fallback`);
  }
  assert.match(block, /background:\s*linear-gradient\(var\(--ac-color-glass\), var\(--ac-color-glass\)\), var\(--ac-color-mesh-base\)/);
});
