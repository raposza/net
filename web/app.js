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

/* The host part of a url, or the text itself when it does not parse. */
function host(url) {
  try {
    return new URL(url).hostname;
  }
  catch (err) {
    return String(url);
  }
}

function hostLine(label, url, other) {
  const div = document.createElement("div");
  const role = document.createElement("span");
  role.className = "role";
  role.textContent = label;
  div.appendChild(role);
  const name = document.createElement("span");
  name.textContent = host(url);
  if (other) {
    name.className = "other";
  }
  div.appendChild(name);
  if (other) {
    const tag = document.createElement("span");
    tag.className = "tag";
    tag.textContent = "other serial";
    div.appendChild(document.createTextNode(" "));
    div.appendChild(tag);
  }
  return div;
}

/* One table per network, in the order the api lists them. A sequencer whose
 * serial is not the one the network reports as current is marked; where the
 * network reports no serial, none is marked. */
function renderSvs(data) {
  const box = document.getElementById("svs");
  box.replaceChildren();
  data.networks.forEach(function (n) {
    const nodes = n.superValidators || [];
    const current = n.deployment ? n.deployment.serialId : null;
    const h3 = document.createElement("h3");
    h3.textContent = n.network;
    const cnt = document.createElement("span");
    cnt.className = "cnt";
    cnt.textContent = nodes.length + " nodes" +
      (current === null || current === undefined ? "" : ", serial " + current);
    h3.appendChild(cnt);
    box.appendChild(h3);
    if (nodes.length === 0) {
      const p = document.createElement("p");
      p.className = "lead";
      p.textContent = "No node reported.";
      box.appendChild(p);
      return;
    }
    const table = document.createElement("table");
    const head = document.createElement("thead");
    const hr = document.createElement("tr");
    ["Super Validator", "Version", "Hosts"].forEach(function (t) {
      const th = document.createElement("th");
      th.textContent = t;
      hr.appendChild(th);
    });
    head.appendChild(hr);
    table.appendChild(head);
    const body = document.createElement("tbody");
    nodes.forEach(function (sv) {
      const tr = document.createElement("tr");
      tr.appendChild(cell(sv.name));
      tr.appendChild(cell(sv.version, "mono"));
      const td = document.createElement("td");
      td.className = "mono hosts";
      if (sv.scan) {
        td.appendChild(hostLine("scan", sv.scan, false));
      }
      (sv.sequencers || []).forEach(function (q) {
        const other = current !== null && current !== undefined && String(q.serial) !== String(current);
        td.appendChild(hostLine("sequencer " + q.serial, q.url, other));
      });
      if (!td.firstChild) {
        td.textContent = "-";
      }
      tr.appendChild(td);
      body.appendChild(tr);
    });
    table.appendChild(body);
    box.appendChild(table);
  });
}

/* The highest Splice release that exists, as the api derives it. A release
 * existing is not a network running it. */
function renderLatest(data) {
  const ver = data.spliceLatest;
  document.getElementById("latest").textContent = ver === null || ver === undefined ? "-" : ver;
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
    const [status, networks, sources] = await Promise.all([
      get("/status"),
      get("/networks"),
      get("/sources")
    ]);
    renderStatus(status);
    renderNetworks(networks);
    renderLatest(networks);
    renderSvs(networks);
    renderSources(sources);
  }
  catch (err) {
    document.getElementById("status").textContent = "The API did not answer: " + err.message;
  }
}

showEnvBar(envFromHost());
load();
setInterval(load, 30000);
