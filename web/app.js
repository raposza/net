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

load();
setInterval(load, 30000);
