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
    : label + " \u2014 " + window.location.hostname + " \u2014 not production";
  bar.hidden = false;
  document.title = (name === "prd" ? "" : "[" + name.toUpperCase() + "] ") +
    "Raposza Network Operations Feed";
}

function cell(text, cls) {
  const td = document.createElement("td");
  if (cls) {
    td.className = cls;
  }
  td.textContent = text === null || text === undefined ? "-" : String(text);
  return td;
}

function tagCell(value) {
  if (!value) {
    return cell(value);
  }
  const td = document.createElement("td");
  const span = document.createElement("span");
  span.className = "tag tag-" + value;
  span.textContent = value;
  td.appendChild(span);
  return td;
}

function rows(tableId, list, build) {
  const body = document.querySelector("#" + tableId + " tbody");
  body.replaceChildren();
  list.forEach(function (item) {
    const tr = document.createElement("tr");
    build(item).forEach(function (td) {
      tr.appendChild(td);
    });
    body.appendChild(tr);
  });
}

function renderNetworks(data) {
  rows("networks", data.networks, function (n) {
    const splice = n.splice || {};
    const next = n.next || {};
    return [
      cell(n.network),
      cell(splice.scheduledVersion, "mono"),
      cell(splice.scheduledFrom, "mono"),
      cell(splice.minimumVersion, "mono"),
      cell(next.version, "mono"),
      cell(next.from, "mono")
    ];
  });
}

/* Upcoming only. An event whose date has passed answers no question a reader of
 * this page came with, and a record withdrawn upstream states nothing at all.
 * Both remain in the API, which is where history is read. */
function renderEvents(data) {
  const dayToday = new Date().toISOString().slice(0, 10);
  const list = (data.events || []).filter(function (e) {
    const from = e.effective && e.effective.from;
    return !e.withdrawn && (!from || from >= dayToday);
  });
  rows("events", list, function (e) {
    return [
      cell(e.network),
      cell(e.kind),
      cell(e.version && e.version.value, "mono"),
      tagCell(e.status),
      cell(e.effective && e.effective.from, "mono"),
      cell(e.title)
    ];
  });
}

function renderSources(data) {
  rows("sources", data.sources, function (s) {
    return [cell(s.id, "mono"), cell(s.publisher), cell(s.authority), cell(s.state)];
  });
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

function renderStatus(status) {
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
  const note = CONTENT_NOTE[status.content];
  if (note) {
    const banner = document.getElementById("banner");
    banner.textContent = note;
    banner.hidden = false;
  }
}

async function load() {
  try {
    const [status, networks, events, sources] = await Promise.all([
      get("/status"),
      get("/networks"),
      get("/events"),
      get("/sources")
    ]);
    renderStatus(status);
    renderNetworks(networks);
    renderEvents(events);
    renderSources(sources);
  }
  catch (err) {
    document.getElementById("status").textContent = "The API did not answer: " + err.message;
  }
}

showEnvBar(envFromHost());
load();
setInterval(load, 30000);
