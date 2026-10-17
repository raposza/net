/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 * Author Claude/bentzn
 */
/* A view of the API. Every value on the page comes from a response below. */

const BASE = "/api/v1";

async function get(path) {
  const res = await fetch(BASE + path, { headers: { accept: "application/json" } });
  if (!res.ok) {
    throw new Error(path + " -> " + res.status);
  }
  return res.json();
}

/* Which environment is this? The hostname answers instantly, so the banner is
 * on screen before any request completes -- it must not depend on an API that
 * may be the thing that is broken. /api/v1/status is then the authority, and a
 * disagreement between the two is itself worth shouting about. */
const ENV_BY_HOST = {
  "net.raposza.com": "prd",
  "rc.net.raposza.com": "rc",
  "stg.net.raposza.com": "stg",
  "dev.net.raposza.com": "dev"
};

const ENV_LABEL = {
  prd: "Production",
  rc: "Release candidate",
  stg: "Staging",
  dev: "Development",
  local: "Local"
};

/* The networks, in the order a reader looks for them. */
const NETWORK_ORDER = ["MAINNET", "TESTNET", "DEVNET"];

const NETWORK_LABEL = {
  MAINNET: "MainNet",
  TESTNET: "TestNet",
  DEVNET: "DevNet"
};

const CHANGE_LABEL = {
  EVENT_CREATED: "new",
  EVENT_CONFIRMED: "confirmed",
  EVENT_CANCELLED: "cancelled",
  EVENT_RESCHEDULED: "rescheduled",
  EVENT_VERSION_ATTACHED: "version set",
  EVENT_WITHDRAWN: "removed upstream",
  EVENT_CORRECTED: "changed"
};

const FIELD_LABEL = {
  "effective.from": "date",
  "effective.to": "end date",
  "version.precision": "precision",
  "version.change": "change",
  "upstream.type": "type",
  sv_version: "SV version",
  migration_id: "migration",
  serial_id: "serial",
  chain_id_suffix: "chain suffix",
  successor_version: "successor version",
  legacy_version: "legacy version",
  scan_url: "scan",
  commit_sha: "commit",
  commit_time: "commit time"
};

/* Source id to the url that is polled, filled from /api/v1/sources. */
let sourceUrl = {};

/* The change log as last loaded, so the filter does not refetch it. */
let changeLog = [];

function envFromHost() {
  return ENV_BY_HOST[window.location.hostname] || "local";
}

function showEnvBar(name, note) {
  const bar = document.getElementById("envbar");
  if (name === "prd" && !note) {
    bar.hidden = true;
    return;
  }
  const label = ENV_LABEL[name] || name;
  bar.className = "envbar envbar-" + (note ? "mismatch" : name);
  bar.textContent = note
    ? "Environment mismatch: " + note
    : label + " — " + window.location.hostname + " — not production";
  bar.hidden = false;
  document.title = (name === "prd" ? "" : "[" + name.toUpperCase() + "] ") +
    "Raposza Network Operations Feed";
}

function isNone(value) {
  return value === null || value === undefined || value === "";
}

function show(value) {
  return isNone(value) ? "-" : String(value);
}

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) {
    node.className = cls;
  }
  if (text !== undefined) {
    node.textContent = text;
  }
  return node;
}

function cell(text, cls) {
  return el("td", cls, show(text));
}

function link(text, href, title, cls) {
  const a = el("a", cls, text);
  a.href = href;
  if (title) {
    a.title = title;
  }
  return a;
}

/* The upstream document a source polls, as a small arrow beside a value. */
function sourceLink(sourceId) {
  const url = sourceUrl[sourceId];
  if (!url) {
    return null;
  }
  const a = link("↗", url, sourceId + " — " + url, "src");
  a.target = "_blank";
  a.rel = "noopener noreferrer";
  return a;
}

/* A value and its provenance: the value opens the published record it came
 * from, which carries the source, the observation and when it was banked; the
 * arrow opens the upstream document. A value with no record is plain text. */
function fill(node, text, eventId, sourceId) {
  if (isNone(text)) {
    node.textContent = "-";
    return node;
  }
  if (eventId) {
    node.appendChild(link(String(text), BASE + "/events/" + encodeURIComponent(eventId),
      "published record " + eventId));
  }
  else {
    node.appendChild(document.createTextNode(String(text)));
  }
  const src = sourceLink(sourceId);
  if (src) {
    node.appendChild(document.createTextNode(" "));
    node.appendChild(src);
  }
  return node;
}

function valueCell(text, eventId, sourceId, cls) {
  return fill(el("td", cls), text, eventId, sourceId);
}

function rows(tableId, list, build, emptyText, columns) {
  const body = document.querySelector("#" + tableId + " tbody");
  body.replaceChildren();
  if (list.length === 0 && emptyText) {
    const tr = el("tr");
    const td = el("td", "empty", emptyText);
    td.colSpan = columns;
    tr.appendChild(td);
    body.appendChild(tr);
    return;
  }
  list.forEach(function (item) {
    const tr = build(item);
    body.appendChild(tr);
  });
}

function rowOf(cells, cls) {
  const tr = el("tr", cls);
  cells.forEach(function (td) {
    tr.appendChild(td);
  });
  return tr;
}

function byNetworkOrder(list) {
  return list.slice().sort(function (a, b) {
    return NETWORK_ORDER.indexOf(a.network) - NETWORK_ORDER.indexOf(b.network);
  });
}

function networkName(name) {
  return isNone(name) ? "-" : (NETWORK_LABEL[name] || name);
}

/* A network as a pill in its own blue, MainNet darkest, so a row is placed at a
 * glance. A record that belongs to no network is a plain dash. */
function netPill(name) {
  if (isNone(name)) {
    return document.createTextNode("-");
  }
  return el("span", "netpill net-" + name, networkName(name));
}

function netCell(name) {
  const td = el("td", "net");
  td.appendChild(netPill(name));
  return td;
}

/* The latest release, and per network the current, minimum, next and reported
 * versions, each with its provenance. */
function renderVersions(data) {
  const latest = document.getElementById("latest");
  latest.replaceChildren();
  fill(latest, data.spliceLatest, data.spliceLatestEventId, "splice-tags");
  rows("versions", byNetworkOrder(data.networks || []), function (n) {
    const splice = n.splice || {};
    const next = n.next || {};
    const dep = n.deployment || {};
    return rowOf([
      netCell(n.network),
      valueCell(splice.scheduledVersion, splice.scheduledEventId, splice.scheduledSourceId, "mono big"),
      agreeCell(n.roster),
      cell(splice.scheduledFrom, "mono"),
      valueCell(splice.minimumVersion, splice.minimumEventId, splice.minimumSourceId, "mono"),
      valueCell(next.version, next.eventId, next.sourceId, "mono"),
      nextDue(next),
      valueCell(dep.version, dep.eventId, dep.sourceId, "mono")
    ]);
  });
}

/* How many nodes report the scheduled version, out of all, with the whole
 * spread on hover. All on it reads as agreement; anything less is marked. */
function rosterText(roster) {
  return (roster.versions || []).map(function (v) {
    return v.count + " on " + v.version;
  }).concat(roster.unreported ? [roster.unreported + " not reported"] : [])
    .concat(roster.unavailable ? [roster.unavailable + " could not be read now"] : []).join(", ");
}

function agreeCell(roster) {
  const td = el("td", "nowrap");
  if (!roster || !roster.nodes || isNone(roster.scheduledVersion)) {
    td.textContent = "-";
    return td;
  }
  const isAll = roster.onScheduled === roster.nodes;
  const chip = el("span", "tag " + (isAll ? "tag-CONFIRMED" : "tag-TENTATIVE"),
    roster.onScheduled + " / " + roster.nodes);
  chip.title = rosterText(roster);
  td.appendChild(chip);
  return td;
}

function nextDue(next) {
  const td = el("td", "mono");
  td.textContent = show(next.from);
  if (!isNone(next.status) && next.status !== "CONFIRMED") {
    td.appendChild(document.createTextNode(" "));
    td.appendChild(el("span", "tag tag-" + next.status, next.status.toLowerCase()));
  }
  return td;
}

function statusTag(status) {
  const td = el("td");
  if (!isNone(status)) {
    td.appendChild(el("span", "tag tag-" + status, status.toLowerCase()));
  }
  else {
    td.textContent = "-";
  }
  return td;
}

function addDays(day, count) {
  const d = new Date(day + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + count);
  return d.toISOString().substring(0, 10);
}

/* The schedule's events of the coming weeks. A cancelled one stays on the list,
 * struck through, so a reader sees it was called off. */
function renderPlanned(data, createdAt) {
  const list = data.planned || [];
  const today = isNone(createdAt) ? new Date().toISOString().substring(0, 10) : createdAt.substring(0, 10);
  document.getElementById("plannedlead").textContent =
    "From the SV Operations Schedule, " + today + " to " + addDays(today, data.days || 28) +
    ". The schedule gives dates without a time zone; cancelled entries stay on the list, struck through.";
  rows("planned", list, function (p) {
    const tdEvent = valueCell(p.title, p.eventId, p.sourceId);
    return rowOf([
      cell(p.from + (isNone(p.to) ? "" : " – " + p.to), "mono nowrap"),
      netCell(p.network),
      tdEvent,
      statusTag(p.status),
      cell(p.version, "mono")
    ], p.status === "CANCELLED" ? "cancelled" : null);
  }, "Nothing on the schedule in this window.", 5);
}

/* The host part of a url, or the text itself when it does not parse. */
function host(url) {
  try {
    return new URL(url).hostname;
  }
  catch (err) {
    return String(url);
  }
}

/* legacy below the network's current serial, successor above it, nothing when
 * it is the current one or the network reports none. */
function serialRole(current, serial) {
  if (isNone(current) || isNone(serial) || String(current) === String(serial)) {
    return null;
  }
  return Number(serial) < Number(current) ? "legacy" : "successor";
}

function hostLine(label, url, role) {
  const div = el("div");
  div.appendChild(el("span", "role", label));
  div.appendChild(el("span", role ? "other" : null, host(url)));
  if (role) {
    div.appendChild(document.createTextNode(" "));
    div.appendChild(el("span", "tag tag-" + role, role));
  }
  return div;
}

/* One collapsible table per network, closed until opened; which ones are open
 * survives the reload every 30 s. A sequencer whose serial is not the one the
 * network reports as current is marked; where the network reports no serial,
 * none is. */
function renderSvs(data) {
  const box = document.getElementById("svs");
  const open = {};
  box.querySelectorAll("details[open]").forEach(function (d) {
    open[d.dataset.network] = true;
  });
  box.replaceChildren();
  byNetworkOrder(data.networks || []).forEach(function (n) {
    const nodes = n.superValidators || [];
    const current = n.deployment ? n.deployment.serialId : null;
    const details = el("details", "svnet");
    details.dataset.network = n.network;
    details.open = !!open[n.network];
    const summary = el("summary");
    summary.appendChild(netPill(n.network));
    summary.appendChild(el("span", "cnt", nodes.length + " nodes" + (isNone(current) ? "" : ", serial " + current) +
      (n.roster && n.roster.nodes ? " \u2014 " + rosterText(n.roster) : "")));
    details.appendChild(summary);
    box.appendChild(details);
    if (nodes.length === 0) {
      details.appendChild(el("p", "lead", "No node reported."));
      return;
    }
    const table = el("table");
    const head = el("thead");
    const hr = el("tr");
    ["Super Validator", "Version", "Hosts"].forEach(function (t) {
      hr.appendChild(el("th", null, t));
    });
    head.appendChild(hr);
    table.appendChild(head);
    const body = el("tbody");
    nodes.forEach(function (sv) {
      const tr = el("tr");
      tr.appendChild(valueCell(sv.name, sv.eventId, null));
      const tdVer = cell(sv.version, "mono nowrap");
      if (!isNone(sv.versionStatus)) {
        const tag = el("span", "tag tag-legacy", "not read now");
        tag.title = "upstream could not read this node's version at the last poll; the version shown is the last one read";
        tdVer.appendChild(document.createTextNode(" "));
        tdVer.appendChild(tag);
      }
      tr.appendChild(tdVer);
      const td = el("td", "mono hosts");
      if (sv.scan) {
        td.appendChild(hostLine("scan", sv.scan, null));
      }
      (sv.sequencers || []).forEach(function (q) {
        td.appendChild(hostLine("sequencer " + q.serial, q.url, serialRole(current, q.serial)));
      });
      if (!td.firstChild) {
        td.textContent = "-";
      }
      tr.appendChild(td);
      body.appendChild(tr);
    });
    table.appendChild(body);
    details.appendChild(table);
  });
}

/* The id links to the exact endpoint that was polled, so a reader can check a
 * published value against upstream by opening it. */
function renderSources(data) {
  rows("sources", data.sources || [], function (s) {
    const tdId = el("td", "mono");
    if (s.url) {
      const a = link(s.id, s.url, s.url);
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      tdId.appendChild(a);
    }
    else {
      tdId.textContent = show(s.id);
    }
    return rowOf([tdId, cell(s.publisher), cell(s.authority), cell(s.state),
      cell(shortTime(s.lastPolledAt), "mono nowrap"), cell(shortTime(s.lastSuccessAt), "mono nowrap"),
      cell(shortTime(s.lastChangedAt), "mono nowrap")]);
  });
}

function shortTime(stamp) {
  return isNone(stamp) ? null : String(stamp).replace("T", " ").substring(0, 16);
}

/* A field value as a reader wants it: a sequencer list as serial and host, a
 * url as its host, anything else as it is. */
function fieldValue(field, value) {
  if (isNone(value)) {
    return "–";
  }
  const text = String(value);
  if (field === "sequencers") {
    return text.trim().split(/ +/).map(function (entry) {
      const pos = entry.indexOf("=");
      return pos > 0 ? entry.substring(0, pos) + ": " + host(entry.substring(pos + 1)) : entry;
    }).join(", ");
  }
  if (/^https?:\/\//.test(text)) {
    return host(text);
  }
  return text;
}

function movedCell(changes) {
  const td = el("td", "moved");
  (changes || []).forEach(function (c) {
    if (c.field === "withdrawn" || c.field === "version_status") {
      return;
    }
    const div = el("div");
    div.appendChild(el("span", "field", FIELD_LABEL[c.field] || c.field));
    div.appendChild(el("span", "old", fieldValue(c.field, c.old)));
    div.appendChild(document.createTextNode(" → "));
    div.appendChild(el("span", "new", fieldValue(c.field, c["new"])));
    td.appendChild(div);
  });
  if (!td.firstChild) {
    td.textContent = "-";
  }
  return td;
}

/* When the change was seen, and - where its source had not been polled for a
 * while before - that it happened somewhere in that silence, not at that time. */
function observedCell(c) {
  const td = cell(shortTime(c.observedAt), "mono nowrap");
  if (c.afterGap) {
    const tag = el("span", "tag tag-TENTATIVE", "after gap");
    tag.title = "First seen after a silence of this source" +
      (isNone(c.afterGap.from) ? "" : " from " + shortTime(c.afterGap.from)) +
      "; it happened somewhere in that span, not at this time";
    td.appendChild(document.createElement("br"));
    td.appendChild(tag);
  }
  return td;
}

function renderChanges() {
  const wanted = document.getElementById("changesnet").value;
  const list = changeLog.filter(function (c) {
    if (wanted === "") {
      return true;
    }
    if (wanted === "-") {
      return isNone(c.network);
    }
    return c.network === wanted;
  });
  document.getElementById("changescnt").textContent =
    list.length + (list.length === 1 ? " change" : " changes");
  rows("changes", list, function (c) {
    const types = (c.types || []).map(function (t) {
      return CHANGE_LABEL[t] || t;
    }).join(", ");
    const subject = isNone(c.title) ? c.subject : c.title;
    return rowOf([
      observedCell(c),
      netCell(c.network),
      valueCell(subject, c.eventId, c.sourceId, show(subject).length <= 32 ? "nowrap" : null),
      cell(types),
      movedCell(c.changes)
    ]);
  }, "Nothing changed in this window.", 5);
}

/* The window, and the spans of it nobody watched: a quiet stretch of the log
 * and an unwatched one look the same unless the page says which is which. */
function loadChanges(data, createdAt) {
  changeLog = data.changes || [];
  const end = isNone(createdAt) ? new Date().toISOString().substring(0, 10) : createdAt.substring(0, 10);
  const from = isNone(data.from) ? addDays(end, -(data.days || 14)) : shortTime(data.from);
  const known = Array.isArray(data.unwatched);
  const gaps = (known ? data.unwatched : []).map(function (g) {
    return shortTime(g.from) + " \u2013 " + shortTime(g.to);
  });
  document.getElementById("changeslead").textContent =
    "Everything the feed saw change since " + from + " UTC, newest first. Observed is when the feed banked " +
    "the body that showed the change, not when it was made upstream. A node upstream could not read, and its " +
    "return, is not a change and is not listed. " +
    (!known ? "This API does not say when the sources were polled, so a quiet stretch may be one nobody watched."
      : gaps.length === 0 ? "The sources were polled throughout."
        : "Nothing was polled, so nothing could be seen, " + gaps.join("; ") + ".");
  renderChanges();
}

/* What a publication is made of. An environment that has banked nothing and one
 * whose index is broken both serve empty tables, and a reader must not have to
 * guess which they are looking at. */
const CONTENT_NOTE = {
  EMPTY: "This environment has banked no observation, so the tables below are empty. " +
    "That is not a statement that nothing is scheduled.",
  UNAVAILABLE: "The index could not be read, so this publication carries no event and " +
    "the networks carry no value."
};

/* The release of Raposza Net the environment serves, from its own status. */
function renderVersion(status) {
  const node = document.getElementById("version");
  node.textContent = "Raposza Net v. " + (status.version || "unknown");
  node.hidden = false;
}

function renderStatus(status) {
  renderVersion(status);
  const claimed = status.environment;
  const guessed = envFromHost();
  if (claimed && claimed !== guessed) {
    showEnvBar(guessed, window.location.hostname + " is serving the " + claimed + " environment");
  }
  document.getElementById("status").textContent =
    "publication " + status.publicationId +
    ", " + status.publicationAgeSeconds + " s old" +
    ", build " + status.buildId +
    ", content " + status.content;
  const banner = document.getElementById("banner");
  const note = CONTENT_NOTE[status.content];
  banner.textContent = note || "";
  banner.hidden = !note;
}

async function load() {
  try {
    const [status, networks, sources, planned, changes] = await Promise.all([
      get("/status"),
      get("/networks"),
      get("/sources"),
      get("/planned"),
      get("/changes")
    ]);
    sourceUrl = {};
    (sources.sources || []).forEach(function (s) {
      sourceUrl[s.id] = s.url;
    });
    renderStatus(status);
    renderVersions(networks);
    renderPlanned(planned, status.publishedAt);
    renderSvs(networks);
    renderSources(sources);
    loadChanges(changes, status.publishedAt);
  }
  catch (err) {
    document.getElementById("status").textContent = "The API did not answer: " + err.message;
  }
}

document.getElementById("changesnet").addEventListener("change", renderChanges);
showEnvBar(envFromHost());
load();
setInterval(load, 30000);
