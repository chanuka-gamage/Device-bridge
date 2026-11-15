const serverUrlInput = document.getElementById("serverUrl");
const tokenInput = document.getElementById("token");
const connectBtn = document.getElementById("connectBtn");
const connStatus = document.getElementById("connStatus");
const deviceListEl = document.getElementById("deviceList");

// Remember last-used settings between launches
serverUrlInput.value = localStorage.getItem("db_server_url") || "";
tokenInput.value = localStorage.getItem("db_token") || "";

const map = L.map("map").setView([0, 0], 2);
L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
  attribution: "&copy; OpenStreetMap contributors",
}).addTo(map);

const markers = {}; // deviceId -> L.marker
const devices = {}; // deviceId -> latest report
let ws = null;
let hasCentered = false;

function timeAgo(iso) {
  const seconds = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}

function isStale(iso) {
  return Date.now() - new Date(iso).getTime() > 5 * 60 * 1000; // 5 min
}

function upsertDevice(entry) {
  devices[entry.deviceId] = entry;

  if (!markers[entry.deviceId]) {
    markers[entry.deviceId] = L.marker([entry.lat, entry.lng]).addTo(map);
  }
  markers[entry.deviceId].setLatLng([entry.lat, entry.lng]);
  markers[entry.deviceId].bindPopup(`<b>${entry.deviceName}</b><br>Battery: ${entry.battery ?? "?"}%`);

  if (!hasCentered) {
    map.setView([entry.lat, entry.lng], 15);
    hasCentered = true;
  }

  renderDeviceList();
}

function renderDeviceList() {
  deviceListEl.innerHTML = "";
  Object.values(devices).forEach((d) => {
    const stale = isStale(d.timestamp);
    const card = document.createElement("div");
    card.className = "device-card";
    card.innerHTML = `
      <div class="name"><span class="dot ${stale ? "stale" : "online"}"></span>${d.deviceName}</div>
      <div class="row"><span>Battery</span><span>${d.battery !== null ? d.battery + "%" : "—"} ${d.charging ? "⚡" : ""}</span></div>
      <div class="row"><span>Accuracy</span><span>${d.accuracy ? Math.round(d.accuracy) + "m" : "—"}</span></div>
      <div class="row"><span>Wi-Fi</span><span>${d.wifiSSID || "—"}</span></div>
      <div class="row"><span>Last update</span><span>${timeAgo(d.timestamp)}</span></div>
    `;
    card.onclick = () => map.setView([d.lat, d.lng], 16);
    deviceListEl.appendChild(card);
  });
}

// Refresh the "time ago" / stale labels every 10s even without new data
setInterval(renderDeviceList, 10000);

async function loadInitialSnapshot(baseUrl, token) {
  try {
    const res = await fetch(`${baseUrl}/devices`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const list = await res.json();
    list.forEach(upsertDevice);
  } catch (e) {
    connStatus.textContent = `Failed to load devices: ${e.message}`;
  }
}

function connect() {
  const baseUrl = serverUrlInput.value.trim().replace(/\/$/, "");
  const token = tokenInput.value.trim();
  if (!baseUrl || !token) {
    connStatus.textContent = "Enter server URL and token first";
    return;
  }
  localStorage.setItem("db_server_url", baseUrl);
  localStorage.setItem("db_token", token);

  loadInitialSnapshot(baseUrl, token);

  const wsUrl = baseUrl.replace(/^http/, "ws") + `/ws?token=${encodeURIComponent(token)}`;
  if (ws) ws.close();
  ws = new WebSocket(wsUrl);

  ws.onopen = () => (connStatus.textContent = "Connected — live");
  ws.onclose = () => (connStatus.textContent = "Disconnected");
  ws.onerror = () => (connStatus.textContent = "Connection error");
  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    if (msg.type === "snapshot") {
      msg.devices.forEach(upsertDevice);
    } else if (msg.type === "update") {
      upsertDevice(msg.device);
    }
  };
}

connectBtn.addEventListener("click", connect);
