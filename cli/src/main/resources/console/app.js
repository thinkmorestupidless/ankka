// The local console. Hand-written, no framework, no build step.
//
// It talks to the aggregation API and never to a registry, a file or a service directly — see
// Source.scala for why. Two consequences show up all over this file and are deliberate:
//
//   * a service is rendered from a list of instances, and that list happens to have one entry.
//     The Services panel shows an instance count even though it always reads 1, because a panel
//     that renders a single address is the one that has to be rebuilt for a deployed console.
//   * a partial trace is labelled "this window does not hold all of it", never "spans aged out".
//     Locally eviction is the only cause; saying so here would make the other cause a new case.

const state = { services: [], selected: null, tab: 'traces' };

// What the reader asked to see of the selected service's topology. Kept across refreshes, so the
// picture does not jump back while they are reading it; reset when another service is selected.
const topology = { name: null, json: null, doc: null, showPlatform: false, kinds: null, focus: null, selected: null };

const $ = (id) => document.getElementById(id);

async function api(path) {
  const response = await fetch(path);
  if (!response.ok) {
    // The platform's error bodies are `{"error": "..."}` and say something specific — a command
    // asked for as a query, a handler that threw. Drop that on the floor here and every caller
    // is left rendering "something went wrong".
    const error = new Error(`${path} -> ${response.status}`);
    try {
      const body = await response.json();
      if (body && body.error) error.detail = body.error;
    } catch (ignored) { /* not every failure has a JSON body */ }
    throw error;
  }
  return response.json();
}

// ── Services ────────────────────────────────────────────────────────────────

async function loadServices() {
  let services = [];
  try {
    services = (await api('/api/services')).services || [];
    $('status').textContent = '';
  } catch (e) {
    // Degrade, never blank: the console itself is still here and should say what it knows.
    $('status').textContent = 'console cannot reach its own API';
  }

  const known = JSON.stringify(state.services.map((s) => s.name));
  state.services = services;
  if (JSON.stringify(services.map((s) => s.name)) !== known) render();

  // A service that has gone must not stay selected with a dead detail pane.
  if (state.selected && !services.some((s) => s.name === state.selected)) {
    state.selected = null;
    render();
  }
}

function render() {
  const list = $('services');
  list.innerHTML = '';
  $('services-empty').hidden = state.services.length > 0;

  for (const service of state.services) {
    const button = document.createElement('button');
    button.className = 'service';
    button.setAttribute('aria-current', String(service.name === state.selected));
    const instances = (service.instances || []).length;
    button.innerHTML =
      `<div class="name"></div>` +
      `<div class="meta">${instances} instance${instances === 1 ? '' : 's'}</div>`;
    button.querySelector('.name').textContent = service.name;
    button.onclick = () => select(service.name);
    list.appendChild(button);
  }

  $('detail-empty').hidden = state.selected !== null;
  for (const panel of ['components', 'topology', 'invoke', 'traces', 'agents']) {
    $(`panel-${panel}`).hidden = state.selected === null || state.tab !== panel;
  }
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.setAttribute('aria-selected', String(tab.dataset.panel === state.tab));
  });
}

function select(name) {
  state.selected = name;
  render();
  refreshDetail();
}

// ── Components ──────────────────────────────────────────────────────────────

async function loadComponents(name) {
  const container = $('components');
  container.innerHTML = '';
  let service;
  try {
    service = await api(`/api/service/${encodeURIComponent(name)}`);
  } catch (e) {
    container.textContent = 'This service stopped answering.';
    return;
  }

  const byKind = {};
  for (const component of service.components || []) {
    (byKind[component.kind] ||= []).push(component);
  }

  for (const kind of Object.keys(byKind).sort()) {
    const group = document.createElement('div');
    group.className = 'kind';
    const heading = document.createElement('h3');
    heading.textContent = kind;
    group.appendChild(heading);
    for (const component of byKind[kind]) {
      const chip = document.createElement('button');
      chip.className = 'component';
      chip.textContent = component.id;
      // A component with no queries has nothing to show: the platform offers commands only, and
      // the console does not run those. Say so by not offering the click.
      if ((component.queries || []).length) {
        chip.onclick = () => inspect(name, component);
      } else {
        chip.disabled = true;
        chip.title = 'This component declares no query handlers, so there is nothing to read.';
      }
      group.appendChild(chip);
    }
    container.appendChild(group);
  }

  $('inspect').hidden = true;
  if (!(service.components || []).length) {
    container.innerHTML = '<p class="empty">No components registered.</p>';
  }
}

/**
 * Read one entity's state, through a query handler the component declared.
 *
 * Only queries appear here, and the service refuses a command even if one is asked for — a
 * `query` accepts only a `ReadOnlyEffect`, so "this cannot persist" is a compiler guarantee and
 * the console leans on it rather than inventing its own idea of what is safe to run.
 */
function inspect(service, component) {
  $('inspect').hidden = false;
  $('inspect-title').textContent = component.id;
  $('inspect-result').innerHTML = '';

  const container = $('inspect-queries');
  container.innerHTML = '';
  for (const query of component.queries || []) {
    const button = document.createElement('button');
    button.className = 'route';
    button.textContent = query;
    button.onclick = () => runQuery(service, component.id, query);
    container.appendChild(button);
  }
}

async function runQuery(service, component, method) {
  const id = $('inspect-id').value.trim();
  const result = $('inspect-result');
  if (!id) {
    result.innerHTML = '<p class="empty">Enter an entity id.</p>';
    return;
  }

  try {
    const path = `/api/query/${encodeURIComponent(service)}/${encodeURIComponent(component)}` +
      `/${encodeURIComponent(id)}/${encodeURIComponent(method)}`;
    const state = await api(path);
    result.innerHTML = '';
    const body = document.createElement('pre');
    body.className = 'body';
    body.textContent = JSON.stringify(state, null, 2);
    result.appendChild(body);
  } catch (e) {
    // Note an id that has never been used is *not* this path: `emptyState` is the platform's
    // defined answer, so an unused id returns a real, empty entity rather than a failure. What
    // lands here is a handler that threw, timed out, or could not serialize its reply — and the
    // endpoint puts that reason in the body, so show it rather than a line that fits every case.
    const why = (e && e.detail) || (e && e.message) || 'the component did not answer';
    result.innerHTML = '';
    const line = document.createElement('p');
    line.className = 'empty';
    line.textContent = why;
    result.appendChild(line);
  }
}

// ── Invoke ──────────────────────────────────────────────────────────────────
//
// The built-in HTTP client, replacing the curl a developer would otherwise write. The request is
// proxied through the console process to the service's own port — a browser cannot read a response
// from a different origin, and teaching a production HTTP server to send CORS headers so that a
// development tool can call it would be letting the tool dictate terms to the thing it observes.
//
// It remains an ordinary request either way: no privilege, matched the same, and refused by an
// endpoint's acl exactly as any other client would be. A 403 here is the platform working.

let selectedRoute = null;

async function loadRoutes(name) {
  const container = $('routes');
  container.innerHTML = '';
  let service;
  try {
    service = await api(`/api/service/${encodeURIComponent(name)}`);
  } catch (e) {
    container.textContent = 'This service stopped answering.';
    return;
  }

  const routes = service.routes || [];
  // A service with "http": false has nothing to invoke. Say so rather than show a dead form.
  $('invoke-none').hidden = routes.length > 0;
  $('invoke-form').hidden = routes.length === 0;
  if (!routes.length) return;

  for (const route of routes) {
    const button = document.createElement('button');
    button.className = 'route';
    button.setAttribute('aria-pressed', 'false');
    button.innerHTML = `<span class="m"></span><span class="p"></span>`;
    button.querySelector('.m').textContent = route.method;
    button.querySelector('.p').textContent = route.path + (route.streaming ? '  ⋯' : '');
    button.onclick = () => chooseRoute(route, container);
    container.appendChild(button);
  }

  chooseRoute(selectedRoute && routes.find((r) => r.path === selectedRoute.path && r.method === selectedRoute.method) || routes[0], container);
}

function chooseRoute(route, container) {
  selectedRoute = route;
  for (const button of container.querySelectorAll('.route')) {
    const method = button.querySelector('.m').textContent;
    const path = button.querySelector('.p').textContent;
    button.setAttribute('aria-pressed', String(method === route.method && path === route.path));
  }
  // The template is the starting point; the developer fills in the parameters.
  $('invoke-path').value = route.path;
  $('invoke-template').textContent = route.streaming
    ? 'streams — output appears as it arrives'
    : route.path.includes('{')
      ? 'replace {…} with real values'
      : '';
  const sends = route.method !== 'GET' && route.method !== 'DELETE';
  $('invoke-body-field').hidden = !sends;
  $('invoke-result').innerHTML = '';
}

async function send(name) {
  const button = $('invoke-send');
  const path = $('invoke-path').value;
  if (path.includes('{')) {
    $('invoke-note').textContent = 'fill in the path parameters first';
    return;
  }
  $('invoke-note').textContent = '';
  button.disabled = true;
  button.textContent = 'Sending…';

  const sends = !$('invoke-body-field').hidden;

  if (selectedRoute.streaming) {
    await stream(name, path, sends);
    button.disabled = false;
    button.textContent = 'Send';
    return;
  }

  try {
    const response = await fetch(`/api/invoke/${encodeURIComponent(name)}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        method: selectedRoute.method,
        path,
        body: sends ? $('invoke-body').value : '',
        contentType: sends ? $('invoke-type').value : '',
      }),
    });
    renderResponse(await response.json());
  } catch (e) {
    renderResponse({ status: 0, body: 'the console could not reach the service', headers: [] });
  } finally {
    button.disabled = false;
    button.textContent = 'Send';
  }
}

/**
 * A streaming response, rendered as it arrives.
 *
 * Waiting for the end would show nothing for the whole of the interesting part — an agent's
 * answer arrives over a minute, and the point of watching is to watch. The body is appended to as
 * chunks land, so a reader sees the shape of the answer forming.
 */
async function stream(name, path, sends) {
  const result = $('invoke-result');
  result.innerHTML = '';

  const line = document.createElement('div');
  line.className = 'status-line';
  const code = document.createElement('span');
  code.className = 'code';
  code.textContent = 'streaming…';
  line.appendChild(code);
  result.appendChild(line);

  const body = document.createElement('pre');
  body.className = 'body';
  result.appendChild(body);

  try {
    const response = await fetch(`/api/invoke-stream/${encodeURIComponent(name)}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        method: selectedRoute.method,
        path,
        body: sends ? $('invoke-body').value : '',
        contentType: sends ? $('invoke-type').value : '',
      }),
    });

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      body.textContent += decoder.decode(value, { stream: true });
      // Follow the tail, the way a terminal would.
      body.scrollTop = body.scrollHeight;
    }
    code.className = 'code ok';
    code.textContent = 'stream ended';
  } catch (e) {
    code.className = 'code failed';
    code.textContent = 'stream interrupted';
  }
}

function renderResponse(response) {
  const result = $('invoke-result');
  result.innerHTML = '';

  const line = document.createElement('div');
  line.className = 'status-line';
  const code = document.createElement('span');
  // A refusal is shown as a refusal, not as a fault: 4xx is the platform saying no on purpose.
  code.className =
    'code ' + (response.status === 0 ? 'failed' : response.status < 400 ? 'ok' : response.status < 500 ? 'refused' : 'failed');
  code.textContent = response.status === 0 ? 'no response' : response.status;
  line.appendChild(code);

  if (response.status >= 400 && response.status < 500) {
    const note = document.createElement('span');
    note.className = 'dim';
    note.textContent = 'refused — the same answer any other client would get';
    line.appendChild(note);
  }
  result.appendChild(line);

  const body = document.createElement('pre');
  body.className = 'body';
  body.textContent = pretty(response.body);
  result.appendChild(body);

  // Invoke, then explain: the trace for what just happened is one click away.
  if (state.tab === 'invoke') {
    const link = document.createElement('button');
    link.className = 'route';
    link.style.marginTop = '10px';
    link.textContent = 'see the trace for this request →';
    link.onclick = () => { state.tab = 'traces'; render(); refreshDetail(); };
    result.appendChild(link);
  }
}

function pretty(body) {
  if (!body) return '(empty)';
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch (e) {
    return body;
  }
}

// ── Traces ──────────────────────────────────────────────────────────────────

async function loadTraces(name) {
  const container = $('traces');
  let window_;
  try {
    window_ = await api(`/api/traces/${encodeURIComponent(name)}`);
  } catch (e) {
    container.innerHTML = '<p class="empty">This service stopped answering.</p>';
    return;
  }

  // Always say this is a window, never a history (FR-017).
  $('traces-note').textContent =
    `Showing the ${window_.held} most recent spans of ${window_.capacity} held` +
    (window_.oldestOverwritten ? ' — older ones have been discarded.' : '.');

  const traces = window_.traces || [];
  $('traces-empty').hidden = traces.length > 0;
  container.innerHTML = '';

  for (const trace of traces) {
    const details = document.createElement('details');
    details.className = 'trace';

    const summary = document.createElement('summary');
    const entry = document.createElement('span');
    entry.className = 'entry';
    entry.textContent = trace.entry;
    const outcome = document.createElement('span');
    outcome.className = `outcome ${trace.outcome}`;
    outcome.textContent = trace.outcome;
    const duration = document.createElement('span');
    duration.className = 'dur';
    duration.textContent = `${trace.durationMillis} ms`;
    summary.append(entry, outcome);
    if (trace.partial) {
      const flag = document.createElement('span');
      flag.className = 'flag';
      flag.textContent = 'partial';
      flag.title = 'This window does not hold all of this trace.';
      summary.appendChild(flag);
    }
    summary.appendChild(duration);
    details.appendChild(summary);

    const body = document.createElement('div');
    body.className = 'body';
    body.textContent = 'Loading…';
    details.appendChild(body);

    details.ontoggle = async () => {
      if (!details.open || details.dataset.loaded) return;
      details.dataset.loaded = '1';
      try {
        const full = await api(`/api/traces/${encodeURIComponent(name)}/${trace.traceId}`);
        body.innerHTML = '';
        renderSpans(body, full.spans || [], full.durationMillis, 0);
        // Time outside every root span — the gap between two roots of one trace, belonging to
        // nobody. Distinct from the per-span gaps `renderSpans` draws beneath their own span, so
        // it says which it is rather than showing a second bare "unattributed" row.
        if (full.unattributedMillis > 0) {
          renderSpan(body, {
            component: 'unattributed',
            handler: 'between spans',
            durationMillis: full.unattributedMillis,
            durationMicros: full.unattributedMillis * 1000,
            outcome: null,
            children: [],
          }, full.durationMillis, 0, true);
        }
      } catch (e) {
        body.textContent = 'This trace is no longer available.';
      }
    };

    container.appendChild(details);
  }
}

function renderSpans(container, spans, total, depth) {
  for (const span of spans) {
    renderSpan(container, span, total, depth, false);
    renderSpans(container, span.children || [], total, depth + 1);
    // The span's own time that none of its children account for, as a sibling of those children.
    // This is usually the answer — an endpoint that spent 2ms in the entity and 80ms waiting on a
    // database has one interesting row, and it is this one. Only spans *with* children have a gap
    // worth stating: a leaf's whole duration is already attributed to the leaf.
    if (span.unattributedMicros > 0 && (span.children || []).length) {
      renderSpan(container, {
        component: 'unattributed',
        handler: '',
        durationMillis: span.unattributedMillis,
        durationMicros: span.unattributedMicros,
        outcome: null,
        children: [],
      }, total, depth + 1, true);
    }
  }
}

function renderSpan(container, span, total, depth, unattributed) {
  const row = document.createElement('div');
  row.className = 'span' + (unattributed ? ' unattributed' : '');
  row.style.paddingLeft = `${depth * 16}px`;

  const who = document.createElement('span');
  who.className = 'who';
  who.textContent = span.handler ? `${span.component}#${span.handler}` : span.component;

  const bar = document.createElement('span');
  bar.className = 'bar';
  const fill = document.createElement('i');
  const share = total > 0 ? Math.max(1, (span.durationMillis / total) * 100) : 0;
  fill.style.width = `${Math.min(100, share)}%`;
  bar.appendChild(fill);

  const time = document.createElement('span');
  time.className = 't';
  time.textContent =
    span.durationMillis > 0 ? `${span.durationMillis} ms` : `${span.durationMicros || 0} µs`;

  row.append(who);
  // An orphan keeps the parent it claimed and says the parent is unknown. Never re-parented:
  // a tree that looks complete and describes something that did not happen is worse than a hole.
  if (span.parentUnknown) {
    const flag = document.createElement('span');
    flag.className = 'flag';
    flag.textContent = 'parent unknown';
    row.appendChild(flag);
  }
  if (span.outcome && span.outcome !== 'Ok') {
    const outcome = document.createElement('span');
    outcome.className = `outcome ${span.outcome}`;
    outcome.textContent = span.outcome;
    row.appendChild(outcome);
  }
  row.append(bar, time);
  container.appendChild(row);
}

// ── Agents ──────────────────────────────────────────────────────────────────
//
// Read from the session entity, not from the trace ring: the conversation is event sourced, so
// the entity is the durable record while the ring is a window that evicts. Cost that disappeared
// because the service got busy would be worse than no cost at all.

async function loadAgents(name) {
  let service;
  try {
    service = await api(`/api/service/${encodeURIComponent(name)}`);
  } catch (e) {
    return;
  }
  const hasAgents = (service.components || []).some((c) => c.kind === 'Agent');
  // A service with no agents shows no panel rather than an empty one.
  $('agents-none').hidden = hasAgents;
  $('agents-form').hidden = !hasAgents;
}

async function openSession(name) {
  const id = $('session-id').value.trim();
  const result = $('session-result');
  if (!id) { result.innerHTML = '<p class="empty">Enter a session id.</p>'; return; }

  let history;
  try {
    history = await api(`/api/session/${encodeURIComponent(name)}/${encodeURIComponent(id)}`);
  } catch (e) {
    result.innerHTML = '<p class="empty">No such session — nothing has been said in it.</p>';
    return;
  }

  result.innerHTML = '';
  const usage = history.usage || {};

  const stats = document.createElement('div');
  stats.className = 'usage';
  stats.appendChild(stat(usage.inputTokens, 'tokens in'));
  stats.appendChild(stat(usage.outputTokens, 'tokens out'));
  // Judgments are priced apart from the text model, so they are their own figure, and only
  // shown for a session that made some.
  if (history.judgmentUsage) {
    stats.appendChild(stat(history.judgmentUsage.inputTokens, 'judgment tokens'));
  }

  // Unknown cost shows as unknown, never as zero: a zero reads as free, which is the one wrong
  // answer that looks like an answer. The price of a model is configuration the platform is told.
  const cost = document.createElement('div');
  cost.className = 'stat';
  const amount = document.createElement('div');
  if (usage.cost === undefined || usage.cost === null) {
    amount.className = 'n unknown';
    amount.textContent = '—';
    amount.title = 'No price is configured for this model, so cost is unknown rather than zero.';
  } else {
    amount.className = 'n';
    amount.textContent = usage.cost;
  }
  const label = document.createElement('div');
  label.className = 'l';
  label.textContent = 'cost';
  cost.append(amount, label);
  stats.appendChild(cost);
  result.appendChild(stats);

  for (const message of history.messages || []) {
    const kind = Object.keys(message)[0] || 'message';
    const value = message[kind] || message;
    const block = document.createElement('div');
    block.className = `msg ${kind}`;
    const role = document.createElement('div');
    role.className = 'role';
    role.textContent = kind;
    const text = document.createElement('div');
    text.className = 'text';
    text.textContent = value.text !== undefined ? value.text : JSON.stringify(value, null, 2);
    block.append(role, text);
    result.appendChild(block);
  }

  if (!(history.messages || []).length) {
    result.appendChild(Object.assign(document.createElement('p'), {
      className: 'empty',
      textContent: 'This session exists but holds no messages.',
    }));
  }
}

function stat(value, label) {
  const box = document.createElement('div');
  box.className = 'stat';
  const n = document.createElement('div');
  n.className = 'n';
  n.textContent = value === undefined ? '0' : value;
  const l = document.createElement('div');
  l.className = 'l';
  l.textContent = label;
  box.append(n, l);
  return box;
}

// ── Topology ────────────────────────────────────────────────────────────────
//
// What is shown, what is left out and where each node goes is decided in topology.js, which has
// tests. This draws what it returns and decides nothing.

const SVG = 'http://www.w3.org/2000/svg';
const GRID = { column: 184, row: 54, width: 152, height: 38, margin: 8 };

async function loadTopology(name) {
  if (topology.name !== name) {
    Object.assign(topology, { name, json: null, doc: null, showPlatform: false, kinds: null, focus: null, selected: null });
  }
  let doc;
  try {
    doc = await api(`/api/topology/${encodeURIComponent(name)}`);
  } catch (e) {
    // A service on a runtime older than the topology answers, and says it cannot report one.
    topology.json = null;
    topology.doc = null;
    $('topology-body').hidden = true;
    $('topology-none').hidden = false;
    $('topology-none').textContent = e.detail || 'This service stopped answering.';
    return;
  }
  // Redrawn only when the service says something new: a picture that is rebuilt every two seconds
  // loses the reader's place for nothing.
  const json = JSON.stringify(doc);
  if (json === topology.json) return;
  topology.json = json;
  topology.doc = doc;
  drawTopology();
}

function drawTopology() {
  const doc = topology.doc;
  if (!doc) return;
  const none = (doc.nodes || []).length === 0;
  $('topology-none').hidden = !none;
  $('topology-none').textContent = none ? 'This service registers no components.' : '';
  $('topology-body').hidden = none;
  if (none) return;

  const shown = AnkkaTopology.view(doc, {
    showPlatform: topology.showPlatform,
    kinds: topology.kinds,
    focus: topology.focus,
  });
  drawControls(doc, shown);
  drawGraph(shown);
  drawText(shown);
  drawDetail(shown);
}

function drawControls(doc, shown) {
  const controls = $('topology-controls');
  controls.innerHTML = '';

  const kinds = [...new Set(doc.nodes.filter((n) => topology.showPlatform || !n.platform).map((n) => n.kind))].sort();
  for (const kind of kinds) {
    const on = !topology.kinds || topology.kinds.includes(kind);
    controls.appendChild(toggle(kindInWords(kind), on, (checked) => {
      const current = new Set(topology.kinds || kinds);
      if (checked) current.add(kind); else current.delete(kind);
      // Every kind on is no filter at all, which is how a kind that appears later is shown.
      topology.kinds = kinds.every((k) => current.has(k)) ? null : [...current];
      drawTopology();
    }));
  }

  const platform = doc.nodes.filter((n) => n.platform).length;
  if (platform > 0) {
    const text = topology.showPlatform ? 'platform components' : `platform components (${platform} left out)`;
    controls.appendChild(toggle(text, topology.showPlatform, (checked) => {
      topology.showPlatform = checked;
      drawTopology();
    }));
  }

  if (topology.focus && shown.nodes.some((n) => n.id === topology.focus)) {
    const all = document.createElement('button');
    all.className = 'plain';
    all.textContent = 'Show all';
    all.onclick = () => { topology.focus = null; drawTopology(); };
    controls.appendChild(all);
  }
}

function toggle(text, checked, onChange) {
  const label = document.createElement('label');
  label.className = 'toggle';
  const box = document.createElement('input');
  box.type = 'checkbox';
  box.checked = checked;
  box.onchange = () => onChange(box.checked);
  label.append(box, document.createTextNode(` ${text}`));
  return label;
}

function svg(tag, attributes) {
  const element = document.createElementNS(SVG, tag);
  for (const [name, value] of Object.entries(attributes || {})) element.setAttribute(name, value);
  return element;
}

function drawGraph(shown) {
  const container = $('topology-graph');
  container.innerHTML = '';
  const columns = Math.max(...shown.nodes.map((n) => n.column), 0) + 1;
  const rows = Math.max(...shown.nodes.map((n) => n.row), 0) + 1;
  const width = GRID.margin * 2 + (columns - 1) * GRID.column + GRID.width;
  const height = GRID.margin * 2 + (rows - 1) * GRID.row + GRID.height;
  const picture = svg('svg', {
    viewBox: `0 0 ${width} ${height}`,
    width,
    height,
    role: 'img',
    'aria-label': AnkkaTopology.describe(shown),
  });

  const at = new Map(shown.nodes.map((node) => [node.id, {
    x: GRID.margin + node.column * GRID.column,
    y: GRID.margin + node.row * GRID.row,
  }]));

  // Connections first, so a node is drawn over the line that reaches it.
  for (const edge of shown.declared) {
    const from = at.get(edge.from);
    const to = at.get(edge.to);
    if (!from || !to) continue;
    // Out of the side that faces the other node: a topic a consumer reads is to its right.
    const forwards = to.x >= from.x;
    const x1 = forwards ? from.x + GRID.width : from.x;
    const x2 = forwards ? to.x : to.x + GRID.width;
    const y1 = from.y + GRID.height / 2;
    const y2 = to.y + GRID.height / 2;
    const bend = (x2 - x1) / 2;
    const line = svg('path', {
      class: 'edge declared',
      d: `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`,
    });
    const title = svg('title');
    title.textContent = `${edge.from} to ${edge.to}: ${edge.kind}`;
    line.appendChild(title);
    picture.appendChild(line);
    picture.appendChild(svg('circle', { class: 'edge-end', cx: x2, cy: y2, r: 3 }));
  }

  for (const node of shown.nodes) {
    const { x, y } = at.get(node.id);
    const classes = ['node'];
    if (node.kind.startsWith('External') || node.kind === 'UnknownCaller') classes.push('outside');
    if (node.platform) classes.push('platform');
    if (node.id === topology.selected) classes.push('selected');
    const group = svg('g', { class: classes.join(' '), transform: `translate(${x} ${y})`, tabindex: 0 });
    group.appendChild(svg('rect', { width: GRID.width, height: GRID.height, rx: 6 }));
    const name = svg('text', { x: 10, y: 16, class: 'name' });
    name.textContent = clip(node.label, 20);
    const kind = svg('text', { x: 10, y: 30, class: 'kind' });
    kind.textContent = describeKind(node) + (node.through.length ? ' · via platform' : '');
    const title = svg('title');
    title.textContent = `${node.label}: ${describeKind(node)}`;
    group.append(title, name, kind);
    const choose = () => { topology.selected = node.id; drawTopology(); };
    group.onclick = choose;
    group.onkeydown = (event) => { if (event.key === 'Enter' || event.key === ' ') choose(); };
    picture.appendChild(group);
  }
  container.appendChild(picture);
}

function clip(text, length) {
  return text.length > length ? `${text.slice(0, length - 1)}…` : text;
}

// A kind as a reader says it: "EventSourcedEntity" is the wire's word.
function kindInWords(kind) {
  return kind.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
}

function describeKind(node) {
  return node.platform ? `${kindInWords(node.kind)}, platform` : kindInWords(node.kind);
}

function drawText(shown) {
  const container = $('topology-text');
  container.innerHTML = '';
  container.appendChild(table(
    'Components',
    ['Name', 'Kind', 'Handlers'],
    shown.nodes.map((node) => [node.label, describeKind(node), node.handlers.map((h) => h.name).join(', ')]),
  ));
  if (shown.declared.length) {
    container.appendChild(table(
      'Declared connections',
      ['From', 'To', 'As'],
      shown.declared.map((edge) => [nameOf(shown, edge.from), nameOf(shown, edge.to), describeConnection(edge.kind)]),
    ));
  }
}

function nameOf(shown, id) {
  const node = shown.nodes.find((n) => n.id === id);
  return node ? node.label : id;
}

function describeConnection(kind) {
  return {
    'events': 'an event subscription',
    'state': 'a state subscription',
    'topic-subscription': 'a topic subscription',
    'topic-publication': 'a topic publication',
  }[kind] || kind;
}

function table(caption, headings, rows) {
  const element = document.createElement('table');
  element.className = 'facts';
  element.createCaption().textContent = caption;
  const head = element.createTHead().insertRow();
  for (const heading of headings) {
    const cell = document.createElement('th');
    cell.scope = 'col';
    cell.textContent = heading;
    head.appendChild(cell);
  }
  const body = element.createTBody();
  for (const row of rows) {
    const line = body.insertRow();
    for (const value of row) line.insertCell().textContent = value;
  }
  return element;
}

function drawDetail(shown) {
  const panel = $('topology-detail');
  const node = shown.nodes.find((n) => n.id === topology.selected);
  panel.hidden = !node;
  panel.innerHTML = '';
  if (!node) return;

  const heading = document.createElement('h3');
  heading.textContent = node.label;
  const kind = document.createElement('p');
  kind.className = 'dim';
  kind.textContent = describeKind(node);
  panel.append(heading, kind);

  list(panel, 'Handlers', node.handlers.map((h) => `${h.name} (${h.streaming ? 'streaming ' : ''}${h.type})`));
  list(panel, 'Reads', shown.declared.filter((e) => e.to === node.id)
    .map((e) => `${nameOf(shown, e.from)}, as ${describeConnection(e.kind)}`));
  list(panel, 'Read by', shown.declared.filter((e) => e.from === node.id)
    .map((e) => `${nameOf(shown, e.to)}, as ${describeConnection(e.kind)}`));
  list(panel, 'Through platform components', node.through.map(describeThrough));

  if (topology.focus !== node.id) {
    const focus = document.createElement('button');
    focus.className = 'plain';
    focus.textContent = 'Focus on this';
    focus.onclick = () => { topology.focus = node.id; drawTopology(); };
    panel.appendChild(focus);
  }
}

// What a component does with a platform component that is left out of the picture.
function describeThrough(entry) {
  const what = {
    'reads': `reads ${entry.component}`,
    'read-by': `is read by ${entry.component}`,
    'calls': `calls ${entry.component}`,
    'called-by': `is called by ${entry.component}`,
  }[entry.relation] || `${entry.relation} ${entry.component}`;
  return entry.handled === undefined ? what : `${what}: ${entry.handled} handled, ${entry.unanswered} unanswered`;
}

function list(panel, title, items) {
  if (!items.length) return;
  const heading = document.createElement('h4');
  heading.textContent = title;
  const element = document.createElement('ul');
  for (const item of items) element.appendChild(document.createElement('li')).textContent = item;
  panel.append(heading, element);
}

// ── Wiring ──────────────────────────────────────────────────────────────────

function refreshDetail() {
  if (!state.selected) return;
  if (state.tab === 'components') loadComponents(state.selected);
  else if (state.tab === 'topology') loadTopology(state.selected);
  else if (state.tab === 'invoke') loadRoutes(state.selected);
  else if (state.tab === 'agents') loadAgents(state.selected);
  else loadTraces(state.selected);
}

$('invoke-send').onclick = () => { if (state.selected) send(state.selected); };
$('session-open').onclick = () => { if (state.selected) openSession(state.selected); };

document.querySelectorAll('.tab').forEach((tab) => {
  tab.onclick = () => {
    state.tab = tab.dataset.panel;
    render();
    refreshDetail();
  };
});

loadServices().then(() => {
  // A service that starts appears within 5 seconds, and one that exits disappears (SC-007).
  setInterval(loadServices, 3000);
  // Traces refresh on their own; the invoke panel does not, because re-rendering a form
  // someone is typing into is the fastest way to make a tool infuriating.
  setInterval(() => { if (state.tab === 'traces') refreshDetail(); }, 3000);
  // The topology every two seconds, so that a call just made is on the page within three.
  setInterval(() => { if (state.tab === 'topology') refreshDetail(); }, 2000);
});
