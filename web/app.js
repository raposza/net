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
  "raposza.com": "prd",
  "rc.raposza.com": "rc",
  "stg.raposza.com": "stg",
  "dev.raposza.com": "dev"
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
    return [
      cell(n.network),
      cell(n.splice && n.splice.currentVersion, "mono"),
      cell(n.splice && n.splice.minimumVersion, "mono"),
      cell(n.synchronizer && n.synchronizer.version, "mono"),
      cell(n.synchronizer && n.synchronizer.serialId, "mono"),
      cell(n.nextEvent, "mono")
    ];
  });
}

function renderEvents(data) {
  rows("events", data.events, function (e) {
    return [
      cell(e.network),
      cell(e.type),
      cell(e.slot && e.slot.period, "mono"),
      cell(e.subject && e.subject.version, "mono"),
      tagCell(e.status),
      cell((e.effective && e.effective.from) + " (" + (e.effective && e.effective.precision) + ")", "mono")
    ];
  });
}

function renderSources(data) {
  rows("sources", data.sources, function (s) {
    return [cell(s.id, "mono"), cell(s.publisher), cell(s.authority), cell(s.state)];
  });
}

function renderStatus(status) {
  const claimed = status.environment;
  const guessed = envFromHost();
  if (claimed && claimed !== guessed) {
    showEnvBar(guessed, window.location.hostname + " is serving the " + claimed + " environment");
  }
  document.getElementById("status").textContent =
    "publication " + status.publicationId +
    ", " + status.publicationAgeSeconds + " s old" +
    ", build " + status.buildId;
  if (status.content === "PLACEHOLDER") {
    const banner = document.getElementById("banner");
    banner.textContent =
      "Placeholder content. No source has been collected; every value below was written by hand.";
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
