// Runs every fixture in console/package/fixtures/topology through the local console's rules.
// `node --test cli/src/test/js`, which is how ConsoleTopologyJsSuite runs it.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const topology = require('../../main/resources/console/topology.js');

const fixtures = path.join(__dirname, '../../../../console/package/fixtures/topology');
const files = fs.readdirSync(fixtures).filter((file) => file.endsWith('.json')).sort();

// What a fixture's `expected` is compared with; see the README beside the fixtures.
function observed(fixture) {
  const shown = topology.view(fixture.document, fixture.options);
  const outside = shown.nodes.filter((node) => node.kind.startsWith('External'));
  return {
    shown: shown.nodes.map((node) => node.id),
    platform: shown.nodes.filter((node) => node.platform).map((node) => node.id),
    positions: Object.fromEntries(shown.nodes.map((node) => [node.id, [node.column, node.row]])),
    declared: shown.declared.map((edge) => `${edge.from}>${edge.to}:${edge.kind}`),
    calls: shown.calls.map((call) => `${call.from}>${call.to}`),
    counts: Object.fromEntries(
      shown.calls.map((call) => [`${call.from}>${call.to}`, topology.totals(call.pairs)]),
    ),
    marks: Object.fromEntries(
      shown.calls.map((call) => [`${call.from}>${call.to}`, topology.edgeMark(call.pairs)]),
    ),
    weights: Object.fromEntries(
      shown.calls.map((call) => [`${call.from}>${call.to}`, topology.weight(call.pairs)]),
    ),
    // A fixture says when it is read and where, so the sentence is the same on every machine.
    observedLine: topology.observedLine(
      fixture.document.window,
      Date.parse(fixture.now || fixture.document.window.since),
      fixture.timeZone || 'UTC',
    ),
    through: Object.fromEntries(
      shown.nodes.filter((node) => node.through.length).map((node) => [node.id, node.through]),
    ),
    hiddenPlatform: shown.hiddenPlatform,
    links: Object.fromEntries(
      outside.map((node) => [node.id, topology.linkFor(node, fixture.services || [])]),
    ),
    labels: Object.fromEntries(shown.nodes.map((node) => [node.id, node.label])),
    describe: topology.describe(shown),
  };
}

test('there are fixtures to run', () => {
  assert.ok(files.length > 0, `no fixtures in ${fixtures}`);
});

for (const file of files) {
  const fixture = JSON.parse(fs.readFileSync(path.join(fixtures, file), 'utf8'));
  test(`${file}: ${fixture.scenario || fixture.about}`, () => {
    const actual = observed(fixture);
    const keys = Object.keys(fixture.expected);
    assert.ok(keys.length > 0, 'a fixture that expects nothing checks nothing');
    for (const key of keys) {
      assert.ok(key in actual, `'${key}' is not something a fixture can expect`);
      // `labels` and `links` name the nodes they are about; the rest are whole.
      const compared =
        key === 'labels' || key === 'links'
          ? Object.fromEntries(Object.keys(fixture.expected[key]).map((id) => [id, actual[key][id]]))
          : actual[key];
      assert.deepEqual(compared, fixture.expected[key], `${file}: ${key}`);
    }
  });
}
