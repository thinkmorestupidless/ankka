import { describe, test } from "node:test";
import assert from "node:assert/strict";
import { LogFollower, lines } from "../src/stream/log-follow.ts";

describe("LogFollower", () => {
  test("sends a first window whole", () => {
    assert.deepEqual(new LogFollower().next("a", ["1", "2"]), ["1", "2"]);
  });

  test("sends only what follows the overlap", () => {
    const f = new LogFollower();
    f.next("a", ["1", "2", "3"]);
    assert.deepEqual(f.next("a", ["2", "3", "4", "5"]), ["4", "5"]);
    assert.deepEqual(f.next("a", ["4", "5"]), []);
  });

  test("a window with no overlap is all new", () => {
    const f = new LogFollower();
    f.next("a", ["1", "2"]);
    assert.deepEqual(f.next("a", ["7", "8"]), ["7", "8"]);
  });

  test("keeps instances apart", () => {
    const f = new LogFollower();
    f.next("a", ["x"]);
    assert.deepEqual(f.next("b", ["x"]), ["x"]);
  });

  test("a line repeated inside one overlap is the documented blind spot", () => {
    const f = new LogFollower();
    f.next("a", ["tick", "tick"]);
    // Two new "tick" lines arrived, but the window cannot say whether they are new.
    assert.deepEqual(f.next("a", ["tick", "tick"]), []);
  });

  test("splits output into lines, keeping blank lines inside it", () => {
    assert.deepEqual(lines("a\n\nb\n"), ["a", "", "b"]);
    assert.deepEqual(lines(""), []);
  });
});
