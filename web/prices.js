/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 * Author Claude/bentzn
 */
/* A view of /api/v1/prices. Every value on the page comes from a response
 * below. The environment banner is the main page's, repeated here so this page
 * loads one script of its own and nothing that fills tables it does not have. */

const BASE = "/api/v1";

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

const NETWORK_ORDER = ["MAINNET", "TESTNET", "DEVNET"];

const NETWORK_LABEL = {
  MAINNET: "MainNet",
  TESTNET: "TestNet",
  DEVNET: "DevNet"
};

/* What each venue's own time is, as the api names it. */
const TIME_IS = {
  ROUND_OPENS: "the round opens",
  TICKER: "the venue's ticker",
  RESPONSE: "the venue's response, not a trade",
  UPDATED: "the aggregator's last update"
};

async function get(path) {
  const res = await fetch(BASE + path, { headers: { accept: "application/json" } });
  if (!res.ok) {
    throw new Error(path + " -> " + res.status);
  }
  return res.json();
}

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
    "Canton Coin in US dollars - Raposza Network Operations Feed";
}

function isNone(value) {
  return value === null || value === undefined || value === "";
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

function shortTime(stamp) {
  return isNone(stamp) ? "-" : String(stamp).replace("T", " ").substring(0, 16);
}

/* A decimal as the source gave it, without the trailing zeros some venues pad
 * it with. Nothing else about it is changed. */
function price(text) {
  if (isNone(text)) {
    return "-";
  }
  const value = String(text);
  return value.indexOf(".") < 0 ? value : value.replace(/0+$/, "").replace(/\.$/, "");
}

function cell(text, cls) {
  return el("td", cls, isNone(text) ? "-" : String(text));
}

function priceCell(p, field, isMain) {
  return el("td", "mono nowrap" + (isMain ? " big" : ""), price(p[field]));
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

/* The exact endpoint the price was read from, so a reader can check a value
 * against its source by opening it: a venue named by its host, a network rate
 * by the document it is read from, whose hosts differ only by network. */
function sourceCell(p) {
  const td = el("td", "mono nowrap");
  if (isNone(p.url)) {
    td.textContent = "-";
    return td;
  }
  const a = el("a", null, (p.kind === "VOTED" ? "DSO document" : host(p.url)) + " ↗");
  a.href = p.url;
  a.title = p.id + " — " + p.url;
  a.target = "_blank";
  a.rel = "noopener noreferrer";
  td.appendChild(a);
  return td;
}

function readCell(p) {
  const td = cell(shortTime(p.readAt), "mono nowrap");
  if (!isNone(p.lastAttemptStatus) && p.lastAttemptStatus !== "OK") {
    const tag = el("span", "tag tag-legacy", isNone(p.readAt) ? "not read" : "stale");
    tag.title = "The read at " + shortTime(p.lastAttemptAt) + " UTC failed: " + p.lastAttemptStatus +
      (isNone(p.lastAttemptDetail) ? "" : ", " + p.lastAttemptDetail) +
      (isNone(p.readAt) ? "" : ". The value shown is from the last read that succeeded.");
    td.appendChild(document.createTextNode(" "));
    td.appendChild(tag);
  }
  return td;
}

function netCell(name) {
  const td = el("td", "net");
  td.appendChild(el("span", "netpill net-" + name, NETWORK_LABEL[name] || name));
  return td;
}

function rows(tableId, list, build, emptyText, columns) {
  const body = document.querySelector("#" + tableId + " tbody");
  body.replaceChildren();
  if (list.length === 0) {
    const tr = el("tr");
    const td = el("td", "empty", emptyText);
    td.colSpan = columns;
    tr.appendChild(td);
    body.appendChild(tr);
    return;
  }
  list.forEach(function (item) {
    body.appendChild(build(item));
  });
}

function rowOf(cells) {
  const tr = el("tr");
  cells.forEach(function (td) {
    tr.appendChild(td);
  });
  return tr;
}

function timeCell(p) {
  const td = cell(shortTime(p.sourceTime), "mono nowrap");
  if (!isNone(p.sourceTime) && TIME_IS[p.sourceTimeIs]) {
    td.title = "When " + TIME_IS[p.sourceTimeIs] + ": " + p.sourceTime;
  }
  return td;
}

function renderPrices(data) {
  const list = data.prices || [];
  const voted = list.filter(function (p) {
    return p.kind === "VOTED";
  }).sort(function (a, b) {
    return NETWORK_ORDER.indexOf(a.network) - NETWORK_ORDER.indexOf(b.network);
  });
  const market = list.filter(function (p) {
    return p.kind !== "VOTED";
  });
  rows("voted", voted, function (p) {
    return rowOf([netCell(p.network), priceCell(p, "last", true), cell(p.round, "mono"), timeCell(p),
      readCell(p), sourceCell(p)]);
  }, "No network rate is defined.", 6);
  rows("market", market, function (p) {
    const tdPair = cell(p.pair, "mono nowrap");
    if (p.kind === "AGGREGATE") {
      tdPair.appendChild(document.createTextNode(" "));
      tdPair.appendChild(el("span", "tag", "average"));
    }
    return rowOf([cell(p.venue), tdPair, priceCell(p, "bid", false), priceCell(p, "ask", false),
      priceCell(p, "last", true), timeCell(p), readCell(p), sourceCell(p)]);
  }, "No venue is defined.", 8);

  const banner = document.getElementById("banner");
  let note = "";
  if (data.collecting === false) {
    note = "This environment reads no price, so the tables carry only what an earlier read left, if anything.";
  }
  else if (isNone(data.polledAt)) {
    note = "No price has been read yet.";
  }
  banner.textContent = note;
  banner.hidden = !note;
  return isNone(data.pollSeconds) ? "" : "read every " + Math.round(data.pollSeconds / 60) + " min, last " +
    shortTime(data.polledAt) + " UTC";
}

/* The release of Raposza Net the environment serves, from its own status. */
function renderVersion(status) {
  const node = document.getElementById("version");
  node.textContent = "Raposza Net v. " + (status.version || "unknown");
  node.hidden = false;
}

function renderStatus(status, readNote) {
  renderVersion(status);
  const claimed = status.environment;
  const guessed = envFromHost();
  if (claimed && claimed !== guessed) {
    showEnvBar(guessed, window.location.hostname + " is serving the " + claimed + " environment");
  }
  document.getElementById("status").textContent =
    (readNote ? "Prices " + readNote + "; " : "") +
    "publication " + status.publicationId +
    ", " + status.publicationAgeSeconds + " s old" +
    ", build " + status.buildId;
}

async function load() {
  try {
    const [status, prices] = await Promise.all([get("/status"), get("/prices")]);
    renderStatus(status, renderPrices(prices));
  }
  catch (err) {
    document.getElementById("status").textContent = "The API did not answer: " + err.message;
  }
}

showEnvBar(envFromHost());
load();
setInterval(load, 60000);
