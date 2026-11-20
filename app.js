"use strict";
/* Ghost BLE Tracking — repo-ready web version.
   HONESTY: a web page cannot power a phone's Bluetooth/Wi-Fi radios.
   The only scan data here comes from the fictional Demo Mode below. */

const STORE_KEY = "ghost_ble_state_v1";
const M_PER_FT = 0.3048;

/* ---------- Bands & channels ---------- */
const BLE_CHANNELS = [];
for (let c = 0; c < 40; c++) BLE_CHANNELS.push({ ch: c, mhz: 2402 + c * 2 });
const BLE_ADV = [37, 38, 39];
const WIFI5_CHANNELS = [
  { ch: 36, mhz: 5180 }, { ch: 44, mhz: 5220 }, { ch: 149, mhz: 5745 },
  { ch: 157, mhz: 5785 }, { ch: 165, mhz: 5825 }
];
const WIFI6_CHANNELS = [
  { ch: 1, mhz: 5955 }, { ch: 33, mhz: 6115 }, { ch: 69, mhz: 6295 },
  { ch: 149, mhz: 6695 }, { ch: 233, mhz: 7115 }
];
const BAND_LABEL = { ble: "2.4 GHz BLE", wifi5: "5 GHz Wi-Fi", wifi6: "6 GHz Wi-Fi 6E" };

const SERVICE_NAMES = {
  "180D": "Heart Rate", "180F": "Battery", "180A": "Device Information",
  "1800": "Generic Access", "1801": "Generic Attribute", "1812": "HID (keyboard/mouse/gamepad)",
  "1802": "Immediate Alert", "1816": "Running Speed and Cadence"
};

/* ---------- Fictional demo data (NOT real devices) ---------- */
/* Addresses use the clearly fictional DEMO-… prefix style rendered as
   DE:MO:… style hex so they can never be mistaken for real hardware. */
const DEMO_DEVICES = [
  { name: "Demo Earbuds One", mac: "DE:00:00:00:00:01", type: "Headphones / earbuds", band: "ble", ch: 37, rssi: -52, connectable: true, services: ["180F", "180A"], mfr: "Demo Audio Co (fictional)" },
  { name: "Demo Wrist Watch", mac: "DE:00:00:00:00:02", type: "Smartwatch / wearable", band: "ble", ch: 38, rssi: -61, connectable: true, services: ["180D", "180F"], mfr: "Demo Wearables (fictional)" },
  { name: "Demo Heart Strap", mac: "DE:00:00:00:00:03", type: "Heart-rate monitor", band: "ble", ch: 39, rssi: -70, connectable: true, services: ["180D"], mfr: "Demo Fitness (fictional)" },
  { name: "Unknown", mac: "DE:00:00:00:00:04", type: "Beacon", band: "ble", ch: 37, rssi: -78, connectable: false, services: [], mfr: "Unknown" },
  { name: "Demo Keyboard", mac: "DE:00:00:00:00:05", type: "Keyboard", band: "ble", ch: 38, rssi: -84, connectable: true, services: ["1812", "180F"], mfr: "Demo Input Co (fictional)" },
  { name: "Demo Phone (fictional)", mac: "DE:00:00:00:00:06", type: "Phone", band: "ble", ch: 39, rssi: -66, connectable: true, services: ["1800", "1801"], mfr: "Demo Mobile (fictional)" },
  { name: "Demo Home Wi-Fi", mac: "DE:00:00:00:00:11", type: "Wi-Fi access point / network", band: "wifi5", ch: 149, rssi: -48, connectable: false, services: [], mfr: "Demo Router Co (fictional)", security: "WPA3" },
  { name: "Demo Office Net", mac: "DE:00:00:00:00:12", type: "Wi-Fi access point / network", band: "wifi5", ch: 36, rssi: -72, connectable: false, services: [], mfr: "Demo Router Co (fictional)", security: "WPA2" },
  { name: "Demo Fast Lane 6E", mac: "DE:00:00:00:00:13", type: "Wi-Fi access point / network", band: "wifi6", ch: 69, rssi: -58, connectable: false, services: [], mfr: "Demo Router Co (fictional)", security: "WPA3" },
  { name: "Demo TV Lounge", mac: "DE:00:00:00:00:07", type: "Smart TV", band: "ble", ch: 37, rssi: -88, connectable: true, services: ["180A"], mfr: "Demo Vision (fictional)" }
];
const DEMO_FLAGS = [
  { mac: "DE:00:00:00:00:21", type: "Wi-Fi access point / network", band: "wifi5", ch: 44, rssi: -44, risk: "High",
    name: "Demo Home Wi-Fi", pattern: "Evil-twin pattern (suspected): a network using the same name as “Demo Home Wi-Fi” is broadcasting from a different BSSID/address than the one seen earlier." },
  { mac: "DE:00:00:00:00:22", type: "Wi-Fi access point / network", band: "wifi5", ch: 157, rssi: -63, risk: "Low",
    name: "Free Airport WiFi (Demo)", pattern: "Generic open hotspot soliciting phones: an open, unencrypted network with a generic name that any nearby phone may auto-join." },
  { mac: "DE:00:00:00:00:23", type: "Other / unknown", band: "ble", ch: 38, rssi: -55, risk: "Watch",
    name: "Unknown", pattern: "BLE advertisement flooding: this source is advertising far more often than normal BLE devices, a pattern sometimes used to spam or track phones." },
  { mac: "DE:00:00:00:00:24", type: "Other / unknown", band: "wifi6", ch: 33, rssi: -69, risk: "Watch",
    name: "Unknown", pattern: "Channel hopping: this transmitter has appeared on many channels in a short time instead of staying on one." },
  { mac: "DE:00:00:00:00:25", type: "Phone", band: "ble", ch: 39, rssi: -59, risk: "Watch",
    name: "Unknown", pattern: "Possible follower signal: this source's estimated distance has stayed roughly constant across scans while you moved." },
  { mac: "DE:00:00:00:00:26", type: "Other / unknown", band: "ble", ch: 37, rssi: -81, risk: "Low",
    name: "Unknown", pattern: "Persistent unknown transmitter: no name and no identifiable services, but present in every scan session so far." }
];

/* ---------- State ---------- */
function defaultState() {
  return {
    demo: false, env: "indoor", chatUpdates: true,
    alertFeet: 10, lostMinutes: 5,
    bandEnabled: { ble: true, wifi5: true, wifi6: true },
    tracked: [],        // {key,name,mac,type,band,targeting,lastDistFt,lastSeenTs,log:[{ts,ft}]}
    history: [],        // finished sessions
    dismissedFlags: []  // flag keys dismissed by user
  };
}
let state = loadState();
function loadState() {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (raw) return Object.assign(defaultState(), JSON.parse(raw));
  } catch (e) { /* corrupted storage -> start fresh */ }
  return defaultState();
}
function saveState() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch (e) { /* storage full/blocked */ }
}

/* Runtime (not persisted) */
let scan = null; // {startedAt, elapsedBeforePause, timerId, tickId, devices:Map, flags:[], feed:[], targeting:bool}
let sweepIndex = 0;
let selectedKey = null;

const $ = (id) => document.getElementById(id);
const els = {};
["headerStatus","demoBanner","noDemoNotice","trackedBadge","historyBadge","scanState","scanElapsed","sweepInfo",
 "countTotal","countBle","countWifi5","countWifi6","countFlags","btnStartScan","btnPauseScan","btnStopScan","scanHint",
 "radarCanvas","bandFilter","toggleBle","toggleWifi5","toggleWifi6","channelsBle","channelsWifi5","channelsWifi6",
 "resultsList","resultsEmpty","deviceDetail","detailContent","btnCloseDetail","btnTrackDetail","liveFeed","feedEmpty",
 "btnStartTargeting","btnStopTargeting","targetingStatus","targetingList","targetingEmpty",
 "alertFeet","lostMinutes","btnSaveAlerts","alertsSaved","trackedList","trackedEmpty",
 "historyList","historyEmpty","btnClearHistory","demoToggle","envSetting","chatUpdatesToggle","btnClearAll","clearAllDone"
].forEach((id) => { els[id] = $(id); });

/* ---------- Distance estimate ---------- */
function pathLossExponent() {
  return state.env === "outdoor" ? 2.0 : state.env === "crowded" ? 3.3 : 2.7;
}
function distanceFt(rssi) {
  const txAt1m = -59; // typical BLE/Wi-Fi RSSI at 1 meter
  const meters = Math.pow(10, (txAt1m - rssi) / (10 * pathLossExponent()));
  return Math.max(1, Math.round(meters / M_PER_FT));
}
function fmtDist(ft) { return ft + " ft (" + (ft * M_PER_FT).toFixed(1) + " m) — estimate"; }
function fmtTime(ts) { return new Date(ts).toLocaleTimeString(); }
function fmtDate(ts) { return new Date(ts).toLocaleString(); }
function channelFreq(band, ch) {
  const list = band === "ble" ? BLE_CHANNELS : band === "wifi5" ? WIFI5_CHANNELS : WIFI6_CHANNELS;
  const hit = list.find((c) => c.ch === ch);
  if (hit) return hit.mhz;
  return band === "ble" ? 2402 + ch * 2 : null;
}
function deviceKey(d) { return d.band + "|" + d.mac; }

/* ---------- Tabs ---------- */
document.querySelectorAll(".tab-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab-btn").forEach((b) => b.classList.toggle("active", b === btn));
    document.querySelectorAll(".tab-panel").forEach((p) => p.classList.toggle("active", p.id === "tab-" + btn.dataset.tab));
  });
});

/* ---------- Channels panel ---------- */
function renderChannels() {
  const counts = scan ? scan.channelCounts : {};
  const render = (list, el, band) => {
    el.innerHTML = list.map((c) => {
      const n = counts[band + ":" + c.ch] || 0;
      const adv = band === "ble" && BLE_ADV.includes(c.ch) ? " · adv" : "";
      return '<span class="chan' + (n > 0 ? " hot" : "") + '">Ch ' + c.ch + " · " + c.mhz + " MHz" + adv + (n ? " · " + n + " heard" : "") + "</span>";
    }).join("");
  };
  render(BLE_CHANNELS.filter((c, i) => i % 4 === 0 || BLE_ADV.includes(c.ch)), els.channelsBle, "ble");
  render(WIFI5_CHANNELS, els.channelsWifi5, "wifi5");
  render(WIFI6_CHANNELS, els.channelsWifi6, "wifi6");
}

/* ---------- Live feed ---------- */
function feedEvent(text) {
  const line = { ts: Date.now(), text: text };
  if (scan) scan.feed.push(line);
  const li = document.createElement("li");
  li.innerHTML = "";
  const t = document.createElement("time"); t.textContent = fmtTime(line.ts);
  li.appendChild(t); li.appendChild(document.createTextNode(text));
  els.liveFeed.prepend(li);
  els.feedEmpty.classList.add("hidden");
}

/* ---------- Radar ---------- */
function drawRadar() {
  const ctx = els.radarCanvas.getContext("2d");
  const W = els.radarCanvas.width, C = W / 2;
  ctx.clearRect(0, 0, W, W);
  ctx.strokeStyle = "#1d332a"; ctx.fillStyle = "#8fb8a4"; ctx.font = "10px system-ui";
  [0.33, 0.66, 1].forEach((f, i) => {
    ctx.beginPath(); ctx.arc(C, C, C * f, 0, Math.PI * 2); ctx.stroke();
    ctx.fillText([10, 25, 50][i] + " ft", C + 4, C - C * f + 12);
  });
  ctx.beginPath(); ctx.moveTo(C, 0); ctx.lineTo(C, W); ctx.moveTo(0, C); ctx.lineTo(W, C); ctx.stroke();
  const sweep = (Date.now() / 900) % (Math.PI * 2);
  ctx.strokeStyle = "rgba(43,255,136,.7)";
  ctx.beginPath(); ctx.moveTo(C, C); ctx.lineTo(C + Math.cos(sweep) * C, C + Math.sin(sweep) * C); ctx.stroke();
  ctx.fillStyle = "#2bff88"; ctx.beginPath(); ctx.arc(C, C, 4, 0, Math.PI * 2); ctx.fill();
  if (!scan) return;
  const colors = { ble: "#2bff88", wifi5: "#ffb02b", wifi6: "#c792ff" };
  const filter = els.bandFilter.value;
  scan.devices.forEach((d) => {
    if (filter !== "all" && d.band !== filter) return;
    const ft = distanceFt(d.rssi);
    const r = Math.min(1, ft / 50) * (C - 8);
    const a = hashAngle(d.mac);
    ctx.fillStyle = colors[d.band];
    ctx.beginPath(); ctx.arc(C + Math.cos(a) * r, C + Math.sin(a) * r, 4, 0, Math.PI * 2); ctx.fill();
  });
}
function hashAngle(mac) {
  let h = 0; for (let i = 0; i < mac.length; i++) h = (h * 31 + mac.charCodeAt(i)) % 628;
  return h / 100;
}

/* ---------- Results ---------- */
function visibleDevices() {
  if (!scan) return [];
  const filter = els.bandFilter.value;
  return Array.from(scan.devices.values())
    .filter((d) => filter === "all" || d.band === filter)
    .sort((a, b) => distanceFt(a.rssi) - distanceFt(b.rssi));
}
function sigBar(rssi) {
  const pct = Math.max(4, Math.min(100, (rssi + 100) * 1.6));
  return '<span class="sig"><i style="width:' + pct + '%"></i></span>';
}
function deviceRow(d, clickable) {
  const ft = distanceFt(d.rssi);
  const li = document.createElement("li");
  if (!clickable) li.className = "no-click";
  li.innerHTML =
    '<div class="d-head"><span class="d-name"></span><span class="d-dist">' + ft + ' ft — estimate</span></div>' +
    '<div class="d-meta"></div>' +
    '<div class="d-meta">RSSI ' + d.rssi + ' dBm ' + sigBar(d.rssi) + ' · ' + BAND_LABEL[d.band] + ' · Ch ' + d.ch + ' · ' + (channelFreq(d.band, d.ch) || "?") + ' MHz</div>' +
    '<div class="d-meta">First seen ' + fmtTime(d.firstSeen) + ' · Last seen ' + fmtTime(d.lastSeen) + '</div>';
  li.querySelector(".d-name").textContent = d.name + " ";
  const demo = document.createElement("span"); demo.className = "demo-tag"; demo.textContent = "DEMO";
  li.querySelector(".d-name").appendChild(demo);
  li.querySelectorAll(".d-meta")[0].textContent = d.type + " · " + d.mac;
  if (clickable) li.addEventListener("click", () => showDetail(d));
  return li;
}
function renderResults() {
  const list = visibleDevices();
  els.resultsList.innerHTML = "";
  list.forEach((d) => els.resultsList.appendChild(deviceRow(d, true)));
  els.resultsEmpty.classList.toggle("hidden", list.length > 0);
}
function renderCounters() {
  if (!scan) {
    els.countTotal.textContent = "0"; els.countBle.textContent = "0";
    els.countWifi5.textContent = "0"; els.countWifi6.textContent = "0"; els.countFlags.textContent = "0";
    return;
  }
  let b = 0, w5 = 0, w6 = 0;
  scan.devices.forEach((d) => { if (d.band === "ble") b++; else if (d.band === "wifi5") w5++; else w6++; });
  els.countTotal.textContent = String(scan.devices.size);
  els.countBle.textContent = String(b); els.countWifi5.textContent = String(w5); els.countWifi6.textContent = String(w6);
  els.countFlags.textContent = String(scan.flags.length);
}

/* ---------- Device detail ---------- */
function showDetail(d) {
  selectedKey = deviceKey(d);
  const ft = distanceFt(d.rssi);
  const services = (d.services || []).length
    ? d.services.map((s) => "0x" + s + (SERVICE_NAMES[s] ? " — " + SERVICE_NAMES[s] : "")).join(", ")
    : "None advertised";
  const hist = (d.distLog || []).map((p) => fmtTime(p.ts) + ": " + p.ft + " ft").join(" · ") || "—";
  els.detailContent.innerHTML =
    "<dl>" +
    "<dt>Name</dt><dd></dd>" +
    "<dt>MAC / BSSID</dt><dd>" + d.mac + ' <span class="demo-tag">DEMO — fictional</span></dd>' +
    "<dt>Type</dt><dd>" + d.type + "</dd>" +
    "<dt>Band</dt><dd>" + BAND_LABEL[d.band] + "</dd>" +
    "<dt>Channel / Frequency</dt><dd>Ch " + d.ch + " · " + (channelFreq(d.band, d.ch) || "?") + " MHz</dd>" +
    "<dt>RSSI</dt><dd>" + d.rssi + " dBm</dd>" +
    "<dt>Distance</dt><dd>" + fmtDist(ft) + "</dd>" +
    (d.band === "ble" ? "<dt>Connectable</dt><dd>" + (d.connectable ? "Yes" : "No") + "</dd>" : "") +
    (d.band === "ble" ? "<dt>Advertised services</dt><dd>" + services + "</dd>" : "") +
    (d.security ? "<dt>Security</dt><dd>" + d.security + "</dd>" : "") +
    "<dt>Manufacturer</dt><dd>" + (d.mfr || "Unknown") + "</dd>" +
    "<dt>First / last seen</dt><dd>" + fmtTime(d.firstSeen) + " / " + fmtTime(d.lastSeen) + "</dd>" +
    "<dt>Distance history (this session)</dt><dd class='log'>" + hist + "</dd>" +
    "</dl>";
  els.detailContent.querySelector("dd").textContent = d.name;
  els.deviceDetail.classList.remove("hidden");
  els.deviceDetail.scrollIntoView({ behavior: "smooth", block: "nearest" });
}
els.btnCloseDetail.addEventListener("click", () => els.deviceDetail.classList.add("hidden"));
els.btnTrackDetail.addEventListener("click", () => {
  if (!scan || !selectedKey) return;
  const d = scan.devices.get(selectedKey) || scan.flags.find((f) => deviceKey(f) === selectedKey);
  if (d) trackDevice(d, false);
});

/* ---------- Tracking ---------- */
function trackDevice(d, targeting) {
  const key = deviceKey(d);
  if (state.tracked.some((t) => t.key === key)) { feedEvent("Already tracking " + d.name + "."); return; }
  state.tracked.push({
    key: key, name: d.name, mac: d.mac, type: d.type, band: d.band, targeting: !!targeting,
    lastDistFt: distanceFt(d.rssi), lastSeenTs: Date.now(), log: [{ ts: Date.now(), ft: distanceFt(d.rssi) }]
  });
  saveState(); renderTracked();
  feedEvent("Now tracking " + d.name + " (" + BAND_LABEL[d.band] + ") — " + distanceFt(d.rssi) + " ft.");
}
function renderTracked() {
  els.trackedBadge.textContent = String(state.tracked.length);
  els.trackedBadge.classList.toggle("hidden", state.tracked.length === 0);
  els.trackedList.innerHTML = "";
  state.tracked.forEach((t) => {
    const li = document.createElement("li"); li.className = "no-click";
    li.innerHTML =
      '<div class="d-head"><span class="d-name"></span><span class="d-dist">' + (t.lastDistFt != null ? t.lastDistFt + " ft — estimate" : "—") + "</span></div>" +
      '<div class="d-meta"></div><div class="d-meta">Last seen ' + (t.lastSeenTs ? fmtTime(t.lastSeenTs) : "never") + "</div>" +
      '<div class="d-meta log"></div>';
    const nameEl = li.querySelector(".d-name");
    nameEl.textContent = t.name + " ";
    if (t.targeting) { const s = document.createElement("span"); s.className = "target-tag"; s.textContent = "⚠ TARGETING SOURCE"; nameEl.appendChild(s); }
    li.querySelectorAll(".d-meta")[0].textContent = t.type + " · " + t.mac + " · " + BAND_LABEL[t.band];
    li.querySelector(".log").textContent = "Distance log: " + t.log.slice(-8).map((p) => fmtTime(p.ts) + " " + p.ft + "ft").join(" · ");
    const btn = document.createElement("button"); btn.type = "button"; btn.textContent = "Stop tracking";
    btn.addEventListener("click", () => {
      state.tracked = state.tracked.filter((x) => x.key !== t.key);
      saveState(); renderTracked();
    });
    li.appendChild(btn);
    els.trackedList.appendChild(li);
  });
  els.trackedEmpty.classList.toggle("hidden", state.tracked.length > 0);
}
function updateTrackedFromScan() {
  if (!scan) return;
  state.tracked.forEach((t) => {
    const d = scan.devices.get(t.key) || scan.flags.find((f) => deviceKey(f) === t.key);
    if (d) {
      const ft = distanceFt(d.rssi);
      const wasFar = t.lastDistFt == null || t.lastDistFt > state.alertFeet;
      t.lastDistFt = ft; t.lastSeenTs = Date.now();
      t.log.push({ ts: Date.now(), ft: ft }); if (t.log.length > 60) t.log.shift();
      if (wasFar && ft <= state.alertFeet) feedEvent("Tracked device entered your alert range: " + t.name + " — " + ft + " ft.");
    } else if (t.lastSeenTs && Date.now() - t.lastSeenTs > state.lostMinutes * 60000 && !t.lostNotified) {
      t.lostNotified = true;
      feedEvent("Tracked device lost: " + t.name + " not seen for " + state.lostMinutes + " min.");
    }
    if (d) t.lostNotified = false;
  });
  saveState(); renderTracked();
}
els.btnSaveAlerts.addEventListener("click", () => {
  state.alertFeet = Math.max(1, parseInt(els.alertFeet.value, 10) || 10);
  state.lostMinutes = Math.max(1, parseInt(els.lostMinutes.value, 10) || 5);
  saveState(); els.alertsSaved.classList.remove("hidden");
  setTimeout(() => els.alertsSaved.classList.add("hidden"), 2000);
});

/* ---------- Targeting ---------- */
function renderFlags() {
  els.targetingList.innerHTML = "";
  const flags = scan ? scan.flags : [];
  flags.forEach((f) => {
    const ft = distanceFt(f.rssi);
    const li = document.createElement("li");
    li.innerHTML =
      '<div class="d-head"><span class="d-name"></span><span><span class="risk risk-' + f.risk + '">' + f.risk + '</span> <span class="d-dist">' + ft + ' ft — estimate</span></span></div>' +
      '<div class="d-meta"></div><div class="d-meta">RSSI ' + f.rssi + ' dBm · ' + BAND_LABEL[f.band] + ' · Ch ' + f.ch + ' · ' + (channelFreq(f.band, f.ch) || "?") + ' MHz</div>' +
      '<p></p><div class="d-meta">First seen ' + fmtTime(f.firstSeen) + ' · Last seen ' + fmtTime(f.lastSeen) + ' · Seen ' + f.timesSeen + '×</div>';
    const nameEl = li.querySelector(".d-name");
    nameEl.textContent = f.name + " ";
    const demo = document.createElement("span"); demo.className = "demo-tag"; demo.textContent = "DEMO";
    nameEl.appendChild(demo);
    li.querySelector(".d-meta").textContent = f.type + " · " + f.mac;
    li.querySelector("p").textContent = f.pattern;
    const trackBtn = document.createElement("button"); trackBtn.type = "button"; trackBtn.textContent = "📌 Track source";
    trackBtn.addEventListener("click", () => trackDevice(f, true));
    const dismissBtn = document.createElement("button"); dismissBtn.type = "button"; dismissBtn.textContent = "Dismiss";
    dismissBtn.addEventListener("click", () => {
      state.dismissedFlags.push(deviceKey(f));
      if (scan) scan.flags = scan.flags.filter((x) => deviceKey(x) !== deviceKey(f));
      saveState(); renderFlags(); renderCounters();
    });
    li.appendChild(trackBtn); li.appendChild(document.createTextNode(" ")); li.appendChild(dismissBtn);
    li.addEventListener("click", (e) => { if (e.target.tagName !== "BUTTON") showDetail(f); });
    els.targetingList.appendChild(li);
  });
  els.targetingEmpty.classList.toggle("hidden", flags.length > 0);
}

/* ---------- History ---------- */
function renderHistory() {
  els.historyBadge.textContent = String(state.history.length);
  els.historyBadge.classList.toggle("hidden", state.history.length === 0);
  els.historyList.innerHTML = "";
  state.history.slice().reverse().forEach((s) => {
    const li = document.createElement("li"); li.className = "no-click";
    li.innerHTML =
      '<div class="d-head"><span class="d-name">' + fmtDate(s.startedAt) + (s.demo ? ' <span class="demo-tag">DEMO</span>' : "") + '</span>' +
      '<span class="d-dist">' + s.devices.length + " devices · " + s.flags + " flags</span></div>" +
      '<div class="d-meta">Duration ' + Math.round(s.durationMs / 1000) + 's' + (s.targeting ? " · Targeting scan" : " · Device scan") + "</div>" +
      '<div class="d-meta"></div><div class="d-meta log"></div>';
    li.querySelectorAll(".d-meta")[1].textContent = s.devices.map((d) => d.name + " (" + d.mac + ", " + d.ft + " ft est.)").join(" · ") || "No devices.";
    li.querySelector(".log").textContent = s.feed.map((f) => fmtTime(f.ts) + " " + f.text).join("  |  ");
    els.historyList.appendChild(li);
  });
  els.historyEmpty.classList.toggle("hidden", state.history.length > 0);
}
els.btnClearHistory.addEventListener("click", () => { state.history = []; saveState(); renderHistory(); });

/* ---------- Scan engine (demo simulation) ---------- */
function enabledBands() {
  return ["ble", "wifi5", "wifi6"].filter((b) => state.bandEnabled[b]);
}
function sweepChannels() {
  const out = [];
  if (state.bandEnabled.ble) BLE_ADV.forEach((ch) => out.push({ band: "ble", ch: ch }));
  if (state.bandEnabled.wifi5) WIFI5_CHANNELS.forEach((c) => out.push({ band: "wifi5", ch: c.ch }));
  if (state.bandEnabled.wifi6) WIFI6_CHANNELS.forEach((c) => out.push({ band: "wifi6", ch: c.ch }));
  return out;
}
function startScan(targeting) {
  if (!state.demo || scan) return;
  scan = {
    startedAt: Date.now(), elapsedBeforePause: 0, pausedAt: null, targeting: !!targeting,
    devices: new Map(), flags: [], feed: [], channelCounts: {}, discovered: 0, flagsAdded: 0
  };
  els.scanState.textContent = "Scanning";
  els.headerStatus.textContent = "● Scanning"; els.headerStatus.classList.add("scanning");
  els.targetingStatus.textContent = targeting ? "Scanning" : "Idle";
  setScanButtons(true);
  feedEvent((targeting ? "Targeting scan started" : "Scan started") + " — bands: " + enabledBands().map((b) => BAND_LABEL[b]).join(", ") + " (Demo Mode, fictional data).");
  scan.timerId = setInterval(tickScan, 900);
  scan.elapsedId = setInterval(updateElapsed, 250);
  tickScan();
}
function tickScan() {
  if (!scan || scan.pausedAt) return;
  // Sweep indicator cycles through enabled channels
  const sweep = sweepChannels();
  if (sweep.length) {
    const s = sweep[sweepIndex++ % sweep.length];
    els.sweepInfo.textContent = BAND_LABEL[s.band] + " · Ch " + s.ch + " · " + (channelFreq(s.band, s.ch) || "?") + " MHz";
  }
  // Discover demo devices gradually (deterministic order, slight RSSI jitter)
  const pool = DEMO_DEVICES.filter((d) => state.bandEnabled[d.band]);
  if (!scan.targeting && scan.discovered < pool.length) {
    const src = pool[scan.discovered++];
    const d = Object.assign({}, src, {
      rssi: src.rssi + Math.round((Math.random() * 6 - 3)),
      firstSeen: Date.now(), lastSeen: Date.now(), distLog: []
    });
    d.distLog.push({ ts: Date.now(), ft: distanceFt(d.rssi) });
    scan.devices.set(deviceKey(d), d);
    scan.channelCounts[d.band + ":" + d.ch] = (scan.channelCounts[d.band + ":" + d.ch] || 0) + 1;
    feedEvent("New device found: " + d.name + " (" + d.type + ") — " + distanceFt(d.rssi) + " ft (" + BAND_LABEL[d.band] + ").");
  } else if (!scan.targeting) {
    // Refresh RSSI / last-seen for known devices
    scan.devices.forEach((d) => {
      d.rssi = Math.max(-95, Math.min(-35, d.rssi + Math.round(Math.random() * 6 - 3)));
      d.lastSeen = Date.now();
      d.distLog.push({ ts: Date.now(), ft: distanceFt(d.rssi) }); if (d.distLog.length > 30) d.distLog.shift();
      scan.channelCounts[d.band + ":" + d.ch] = (scan.channelCounts[d.band + ":" + d.ch] || 0) + 1;
    });
  }
  // Targeting flags appear during either scan type
  const flagPool = DEMO_FLAGS.filter((f) => state.bandEnabled[f.band] && !state.dismissedFlags.includes(deviceKey(f)));
  const wantFlags = scan.targeting ? flagPool.length : Math.min(2, flagPool.length);
  if (scan.flagsAdded < wantFlags) {
    const src = flagPool[scan.flagsAdded++];
    const f = Object.assign({}, src, { firstSeen: Date.now(), lastSeen: Date.now(), timesSeen: 1, services: [], connectable: false, mfr: "Unknown", distLog: [{ ts: Date.now(), ft: distanceFt(src.rssi) }] });
    scan.flags.push(f);
    feedEvent("Targeting pattern flagged: " + f.pattern.split(":")[0] + " — " + distanceFt(f.rssi) + " ft (" + BAND_LABEL[f.band] + ").");
  } else {
    scan.flags.forEach((f) => { f.lastSeen = Date.now(); f.timesSeen++; });
  }
  renderCounters(); renderResults(); renderFlags(); renderChannels(); updateTrackedFromScan(); drawRadar();
}
function updateElapsed() {
  if (!scan) { els.scanElapsed.textContent = "0:00"; return; }
  const end = scan.pausedAt || Date.now();
  const sec = Math.floor((scan.elapsedBeforePause + (end - scan.startedAt)) / 1000);
  els.scanElapsed.textContent = Math.floor(sec / 60) + ":" + String(sec % 60).padStart(2, "0");
  drawRadar();
}
function pauseScan() {
  if (!scan || scan.pausedAt) return;
  scan.pausedAt = Date.now();
  els.scanState.textContent = "Paused";
  feedEvent("Scan paused.");
}
function resumeScan() {
  if (!scan || !scan.pausedAt) return;
  scan.elapsedBeforePause += scan.pausedAt - scan.startedAt;
  scan.startedAt = Date.now(); scan.pausedAt = null;
  els.scanState.textContent = "Scanning";
  feedEvent("Scan resumed.");
}
function stopScan() {
  if (!scan) return;
  clearInterval(scan.timerId); clearInterval(scan.elapsedId);
  const durationMs = scan.elapsedBeforePause + ((scan.pausedAt || Date.now()) - scan.startedAt);
  feedEvent("Scan finished — " + scan.devices.size + " devices, " + scan.flags.length + " targeting flags, " + Math.round(durationMs / 1000) + "s.");
  state.history.push({
    startedAt: scan.startedAt, durationMs: durationMs, targeting: scan.targeting, demo: true,
    flags: scan.flags.length,
    devices: Array.from(scan.devices.values()).map((d) => ({ name: d.name, mac: d.mac, ft: distanceFt(d.rssi) })),
    feed: scan.feed.slice()
  });
  saveState(); renderHistory();
  scan = null;
  els.scanState.textContent = "Finished";
  els.headerStatus.textContent = "● Idle"; els.headerStatus.classList.remove("scanning");
  els.targetingStatus.textContent = "Idle";
  els.sweepInfo.textContent = "—";
  setScanButtons(false);
  renderCounters();
}
function setScanButtons(running) {
  els.btnStartScan.disabled = running || !state.demo;
  els.btnPauseScan.disabled = !running;
  els.btnStopScan.disabled = !running;
  els.btnStartTargeting.disabled = running || !state.demo;
  els.btnStopTargeting.disabled = !running;
  els.btnPauseScan.textContent = scan && scan.pausedAt ? "▶ Resume" : "⏸ Pause";
}
els.btnStartScan.addEventListener("click", () => startScan(false));
els.btnStartTargeting.addEventListener("click", () => startScan(true));
els.btnStopScan.addEventListener("click", stopScan);
els.btnStopTargeting.addEventListener("click", stopScan);
els.btnPauseScan.addEventListener("click", () => {
  if (!scan) return;
  if (scan.pausedAt) resumeScan(); else pauseScan();
  setScanButtons(true);
});
els.bandFilter.addEventListener("change", () => { renderResults(); drawRadar(); });
[["toggleBle", "ble"], ["toggleWifi5", "wifi5"], ["toggleWifi6", "wifi6"]].forEach(([id, band]) => {
  els[id].addEventListener("change", () => { state.bandEnabled[band] = els[id].checked; saveState(); });
});

/* ---------- Settings ---------- */
function applyDemoUI() {
  els.demoBanner.classList.toggle("hidden", !state.demo);
  els.noDemoNotice.classList.toggle("hidden", state.demo);
  els.scanHint.classList.toggle("hidden", state.demo);
  if (!state.demo) setScanButtons(false); else if (!scan) setScanButtons(false);
  els.btnStartScan.disabled = !state.demo || !!scan;
  els.btnStartTargeting.disabled = !state.demo || !!scan;
}
els.demoToggle.addEventListener("change", () => {
  state.demo = els.demoToggle.checked;
  if (!state.demo) {
    if (scan) { clearInterval(scan.timerId); clearInterval(scan.elapsedId); scan = null; }
    state.history = state.history.filter((h) => !h.demo);
    state.dismissedFlags = [];
    els.liveFeed.innerHTML = ""; els.feedEmpty.classList.remove("hidden");
    els.scanState.textContent = "Idle"; els.sweepInfo.textContent = "—";
    renderResults(); renderFlags(); renderCounters(); renderHistory(); renderChannels(); drawRadar();
  }
  saveState(); applyDemoUI();
});
els.demoQuickToggle.addEventListener("click", () => {
  els.demoToggle.checked = true;
  els.demoToggle.dispatchEvent(new Event("change"));
  document.querySelector('[data-tab="settings"]').click();
  document.querySelector('[data-tab="scanner"]').click();
});
els.envSetting.addEventListener("change", () => {
  state.env = els.envSetting.value; saveState();
  renderResults(); drawRadar();
});
els.chatUpdatesToggle.addEventListener("change", () => { state.chatUpdates = els.chatUpdatesToggle.checked; saveState(); });
els.btnClearAll.addEventListener("click", () => {
  localStorage.removeItem(STORE_KEY);
  state = defaultState();
  if (scan) { clearInterval(scan.timerId); clearInterval(scan.elapsedId); scan = null; }
  initControls(); renderAll();
  els.clearAllDone.classList.remove("hidden");
  setTimeout(() => els.clearAllDone.classList.add("hidden"), 2500);
});

/* ---------- Init ---------- */
function initControls() {
  els.demoToggle.checked = state.demo;
  els.envSetting.value = state.env;
  els.chatUpdatesToggle.checked = state.chatUpdates;
  els.alertFeet.value = state.alertFeet;
  els.lostMinutes.value = state.lostMinutes;
  els.toggleBle.checked = state.bandEnabled.ble;
  els.toggleWifi5.checked = state.bandEnabled.wifi5;
  els.toggleWifi6.checked = state.bandEnabled.wifi6;
}
function renderAll() {
  renderChannels(); renderResults(); renderFlags(); renderTracked(); renderHistory(); renderCounters(); drawRadar(); applyDemoUI();
}
initControls();
renderAll();
setInterval(() => { if (!scan) drawRadar(); }, 1200);
