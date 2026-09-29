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

/* Versions compare segment by segment as numbers, as the dataset does:
 * compared as text, 0.10.4 sorts below 0.8.2. A segment that is not a number
 * counts as 0, the same rule the publisher applies. */
function part(arrPart, posPart) {
  if (posPart >= arrPart.length) {
    return 0;
  }
  const text = arrPart[posPart];
  return /^[+-]?[0-9]+$/.test(text) ? parseInt(text, 10) : 0;
}

function compareVersions(verLeft, verRight) {
  const arrLeft = verLeft.split(".");
  const arrRight = verRight.split(".");
  const cntPart = Math.max(arrLeft.length, arrRight.length);
  for (let posPart = 0; posPart < cntPart; posPart++) {
    const nLeft = part(arrLeft, posPart);
    const nRight = part(arrRight, posPart);
    if (nLeft !== nRight) {
      return nLeft < nRight ? -1 : 1;
    }
  }
  return 0;
}

/* The highest SOFTWARE_RELEASE the events carry, withdrawn ones excluded --
 * the same derivation as splice-latest in the published versions.yml. */
function renderLatest(data) {
  let verBest = null;
  (data.events || []).forEach(function (e) {
    if (e.kind !== "SOFTWARE_RELEASE" || String(e.withdrawn) === "true") {
      return;
    }
    const value = e.version && e.version.value;
    if (value === null || value === undefined) {
      return;
    }
    const verOne = String(value);
    if (verBest === null || compareVersions(verOne, verBest) > 0) {
      verBest = verOne;
    }
  });
  document.getElementById("latest").textContent = verBest === null ? "-" : verBest;
}

/* The id links to the exact endpoint that was polled, so a reader can check a
 * published value against upstream by opening it. */
function linkCell(text, url, cls) {
  if (!url) {
    return cell(text, cls);
  }
  const td = document.createElement("td");
  if (cls) {
    td.className = cls;
  }
  const a = document.createElement("a");
  a.href = url;
  a.target = "_blank";
  a.rel = "noopener noreferrer";
  a.title = url;
  a.textContent = text === null || text === undefined ? "-" : String(text);
  td.appendChild(a);
  return td;
}

function renderSources(data) {
  rows("sources", data.sources, function (s) {
    return [linkCell(s.id, s.url, "mono"), cell(s.publisher), cell(s.authority), cell(s.state)];
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
    renderLatest(events);
    renderSources(sources);
  }
  catch (err) {
    document.getElementById("status").textContent = "The API did not answer: " + err.message;
  }
}

showEnvBar(envFromHost());
load();
setInterval(load, 30000);
