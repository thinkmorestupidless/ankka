// The rules for reading a topology: what is shown, what is left out, and where each thing goes.
//
// Pure functions, document in and plain data out, with no page and no state. `app.js` draws what
// these return and decides nothing of its own, so a rule about a topology is in one file and has a
// test: `cli/src/test/js/topology.test.js` runs every fixture in
// `console/package/fixtures/topology/` through them, and the installation console's own layout is
// held to the same fixtures. Loaded by the page as a plain script and by Node as a module; there is
// no build step.
(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.AnkkaTopology = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // The handled and the unanswered calls of an observed call, each added up over its pairs. Two
  // numbers, never one: a call can be both, and adding them would count it twice.
  function totals(pairs) {
    const sum = { handled: 0, unanswered: 0 };
    for (const pair of pairs || []) {
      const handled = pair.handled || {};
      const unanswered = pair.unanswered || {};
      sum.handled += (handled.ok || 0) + (handled.refused || 0) + (handled.failed || 0);
      sum.unanswered += (unanswered.timedOut || 0) + (unanswered.undelivered || 0);
    }
    return sum;
  }

  // Whether an observed call is going wrong, which is what a reader is warned about: a handler
  // that failed, or a call nothing answered. A refusal is a handler saying no, which is the
  // handler working, so refusals alone never mark a call however many there are.
  function edgeMark(pairs) {
    for (const pair of pairs || []) {
      const handled = pair.handled || {};
      const unanswered = pair.unanswered || {};
      if ((handled.failed || 0) > 0) return 'warning';
      if ((unanswered.timedOut || 0) + (unanswered.undelivered || 0) > 0) return 'warning';
    }
    return null;
  }

  // How heavily an observed call is drawn, from 1 to 5: one step for each power of ten of the
  // calls that were handled, so a busy call stands out and a line never grows without limit.
  function weight(pairs) {
    const handled = totals(pairs).handled;
    return 1 + Math.min(4, Math.floor(Math.log10(Math.max(1, handled))));
  }

  // The sentence that goes wherever observed calls are shown. It says how far back they reach,
  // and that a call not made in that time is not there: what is shown is what happened, never
  // everything the service can do. `now` is in milliseconds; `timeZone` is the reader's unless
  // one is given.
  function observedLine(window, now, timeZone) {
    const seconds = (window && window.seconds) || 0;
    const since = window ? Date.parse(window.since) : NaN;
    // The window reaches back to the service's start when the service is younger than it.
    const sinceStart = Number.isFinite(since) && since > now - seconds * 1000;
    const from = sinceStart ? ` (since ${clock(since, timeZone)} when the service started)` : '';
    return `Calls observed in the last ${inWords(seconds)}${from}. Calls not made in that time are not shown.`;
  }

  function inWords(seconds) {
    const count = (n, one, many) => `${n} ${n === 1 ? one : many}`;
    if (seconds >= 3600 && seconds % 3600 === 0) return count(seconds / 3600, 'hour', 'hours');
    if (seconds >= 60 && seconds % 60 === 0) return count(seconds / 60, 'minute', 'minutes');
    return count(seconds, 'second', 'seconds');
  }

  function clock(millis, timeZone) {
    return new Intl.DateTimeFormat('en-GB', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      timeZone,
    }).format(new Date(millis));
  }

  // What a node is called on the page. Its id says what kind of thing it is; a reader wants its name.
  function label(node) {
    if (node.kind === 'UnknownCaller') return 'unknown caller';
    if (node.id === 'service:(other)') return 'other services';
    if (node.kind === 'ExternalService') return node.id.slice('service:'.length).split('/').pop();
    if (node.kind === 'Topic') return node.id.slice('topic:'.length);
    if (node.kind === 'ExternalComponent') return node.id.slice('external:'.length);
    if (node.kind === 'Endpoint') return node.id.slice('endpoint:'.length);
    return node.id;
  }

  // Where a called service's own topology can be opened from this one.
  //
  // Only a service that was called, and only when exactly one service on this machine has its
  // name: that name is the one the runtime finds a local service by, so one match is the service
  // the call reached, and two would be a guess. A source outside the service names a component,
  // not a service, so there is nothing to open.
  function linkFor(node, services) {
    if (node.kind !== 'ExternalService') return { service: null, note: null };
    if (node.id === 'service:(other)') return { service: null, note: 'other services' };
    const name = label(node);
    const matches = (services || []).filter((service) => service === name);
    return matches.length === 1
      ? { service: name, note: null }
      : { service: null, note: 'not running here' };
  }

  // The platform's own components, left out, and what each visible component had to do with them.
  //
  // Nothing is lost by leaving one out: a connection or a call between a visible component and a
  // hidden one stays on the visible one, as something it does through a platform component. What
  // two platform components do between themselves is the platform's business and goes with them.
  function fold(nodes, declared, calls) {
    const hidden = new Set(nodes.filter((node) => node.platform).map((node) => node.id));
    const through = new Map();
    const note = (visible, entry) => {
      if (!through.has(visible)) through.set(visible, []);
      through.get(visible).push(entry);
    };

    for (const edge of declared) {
      if (hidden.has(edge.from) && !hidden.has(edge.to)) {
        note(edge.to, { component: edge.from, relation: 'reads', kind: edge.kind });
      } else if (!hidden.has(edge.from) && hidden.has(edge.to)) {
        note(edge.from, { component: edge.to, relation: 'read-by', kind: edge.kind });
      }
    }
    for (const call of calls) {
      const counts = totals(call.pairs);
      if (!hidden.has(call.from) && hidden.has(call.to)) {
        note(call.from, { component: call.to, relation: 'calls', ...counts });
      } else if (hidden.has(call.from) && !hidden.has(call.to)) {
        note(call.to, { component: call.from, relation: 'called-by', ...counts });
      }
    }

    const visible = (edge) => !hidden.has(edge.from) && !hidden.has(edge.to);
    return {
      nodes: nodes
        .filter((node) => !hidden.has(node.id))
        .map((node) => ({ ...node, through: sorted(through.get(node.id) || [], throughOrder) })),
      declared: declared.filter(visible),
      calls: calls.filter(visible),
      hidden: hidden.size,
    };
  }

  // Only the kinds asked for. A connection to something left out goes with it: the reader asked
  // not to see that end.
  function filterKinds(nodes, declared, calls, kinds) {
    if (!kinds) return { nodes, declared, calls };
    const wanted = new Set(kinds);
    return within(nodes.filter((node) => wanted.has(node.kind)), declared, calls);
  }

  // One component and what it is directly connected to, by a declared connection or a call in
  // either direction, and nothing else.
  function focus(nodes, declared, calls, id) {
    if (!id || !nodes.some((node) => node.id === id)) return { nodes, declared, calls };
    const near = new Set([id]);
    for (const edge of [...declared, ...calls]) {
      if (edge.from === id) near.add(edge.to);
      if (edge.to === id) near.add(edge.from);
    }
    return within(nodes.filter((node) => near.has(node.id)), declared, calls);
  }

  function within(nodes, declared, calls) {
    const ids = new Set(nodes.map((node) => node.id));
    const both = (edge) => ids.has(edge.from) && ids.has(edge.to);
    return { nodes, declared: declared.filter(both), calls: calls.filter(both) };
  }

  // A column for each layer that has something in it, left to right, and a row for each node in
  // its column, by id. The same document is always the same picture.
  function place(nodes) {
    const layers = [...new Set(nodes.map((node) => node.layer))].sort((a, b) => a - b);
    const rows = new Map();
    return sorted(nodes, (a, b) => a.layer - b.layer || compare(a.id, b.id)).map((node) => {
      const column = layers.indexOf(node.layer);
      const row = rows.get(column) || 0;
      rows.set(column, row + 1);
      return { ...node, column, row };
    });
  }

  // What to draw of a topology, given what the reader asked to see.
  //
  //   showPlatform  the platform's own components too (default: left out, and folded)
  //   kinds         only these kinds of node (default: every kind)
  //   focus         only this node and what it is directly connected to (default: everything)
  //
  // In that order: what is folded away is decided first, so focusing on a component never brings
  // back something the reader left out.
  function view(doc, options) {
    const asked = { showPlatform: false, kinds: null, focus: null, ...(options || {}) };
    const all = (doc.nodes || []).map((node) => ({ ...node, through: [] }));
    const folded = asked.showPlatform
      ? { nodes: all, declared: doc.declared || [], calls: doc.calls || [], hidden: 0 }
      : fold(all, doc.declared || [], doc.calls || []);
    const kinds = filterKinds(folded.nodes, folded.declared, folded.calls, asked.kinds);
    const focused = focus(kinds.nodes, kinds.declared, kinds.calls, asked.focus);
    return {
      nodes: place(focused.nodes).map((node) => ({ ...node, label: label(node) })),
      declared: sorted(focused.declared, edgeOrder),
      calls: sorted(focused.calls, edgeOrder),
      hiddenPlatform: folded.hidden,
    };
  }

  // What a picture says, for a reader who cannot see it.
  function describe(shown) {
    const count = (n, one, many) => `${n} ${n === 1 ? one : many}`;
    return [
      count(shown.nodes.length, 'component', 'components'),
      count(shown.declared.length, 'declared connection', 'declared connections'),
      count(shown.calls.length, 'observed call', 'observed calls'),
    ].join(', ');
  }

  function compare(a, b) {
    return a < b ? -1 : a > b ? 1 : 0;
  }

  function sorted(items, order) {
    return [...items].sort(order);
  }

  function edgeOrder(a, b) {
    return compare(a.from, b.from) || compare(a.to, b.to) || compare(a.kind || '', b.kind || '');
  }

  function throughOrder(a, b) {
    return compare(a.component, b.component) || compare(a.relation, b.relation);
  }

  return { view, linkFor, label, totals, describe, edgeMark, weight, observedLine };
});
