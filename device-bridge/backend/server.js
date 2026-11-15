// Device Bridge backend
// - Phone POSTs reports to /report
// - Desktop app connects via WebSocket to /ws for live pushes, and can GET /devices anytime
//
// Auth: a single shared secret token (set DEVICE_TOKEN env var). Phone and desktop
// both send it as "Authorization: Bearer <token>". Change it before deploying.

const express = require("express");
const cors = require("cors");
const http = require("http");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 3000;
const TOKEN = process.env.DEVICE_TOKEN || "change-this-secret-token";
const MAX_HISTORY_POINTS = 200; // per device, for the live trail on the map

const app = express();
app.use(cors());
app.use(express.json());

// In-memory store: { [deviceId]: { latest: {...}, history: [...] } }
const devices = {};

function checkAuth(req, res, next) {
  const header = req.headers["authorization"] || "";
  const provided = header.replace(/^Bearer\s+/i, "");
  if (provided !== TOKEN) {
    return res.status(401).json({ error: "unauthorized" });
  }
  next();
}

// Phone calls this periodically (e.g. every 30-60s, or immediately on big location change)
app.post("/report", checkAuth, (req, res) => {
  const {
    deviceId,
    deviceName,
    lat,
    lng,
    accuracy,
    battery,       // 0-100
    charging,      // bool
    wifiSSID,      // optional
    note,          // optional free text status
  } = req.body;

  if (!deviceId || lat === undefined || lng === undefined) {
    return res.status(400).json({ error: "deviceId, lat, lng are required" });
  }

  const entry = {
    deviceId,
    deviceName: deviceName || deviceId,
    lat,
    lng,
    accuracy: accuracy ?? null,
    battery: battery ?? null,
    charging: charging ?? null,
    wifiSSID: wifiSSID ?? null,
    note: note ?? null,
    timestamp: new Date().toISOString(),
  };

  if (!devices[deviceId]) {
    devices[deviceId] = { latest: null, history: [] };
  }
  devices[deviceId].latest = entry;
  devices[deviceId].history.push(entry);
  if (devices[deviceId].history.length > MAX_HISTORY_POINTS) {
    devices[deviceId].history.shift();
  }

  broadcast({ type: "update", device: entry });
  res.json({ ok: true });
});

// Desktop app calls this on load to get current state of everything
app.get("/devices", checkAuth, (req, res) => {
  const result = Object.values(devices).map((d) => d.latest).filter(Boolean);
  res.json(result);
});

// Desktop app calls this to get the location trail for one device
app.get("/devices/:id/history", checkAuth, (req, res) => {
  const d = devices[req.params.id];
  if (!d) return res.status(404).json({ error: "not found" });
  res.json(d.history);
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });

function broadcast(payload) {
  const msg = JSON.stringify(payload);
  wss.clients.forEach((client) => {
    if (client.readyState === 1) client.send(msg);
  });
}

wss.on("connection", (ws, req) => {
  // Simple auth via query string for the WS handshake: /ws?token=...
  const url = new URL(req.url, "http://localhost");
  if (url.searchParams.get("token") !== TOKEN) {
    ws.close(1008, "unauthorized");
    return;
  }
  // Send current snapshot on connect
  const snapshot = Object.values(devices).map((d) => d.latest).filter(Boolean);
  ws.send(JSON.stringify({ type: "snapshot", devices: snapshot }));
});

server.listen(PORT, () => {
  console.log(`Device bridge backend running on port ${PORT}`);
  console.log(`Token in use: ${TOKEN === "change-this-secret-token" ? "DEFAULT - CHANGE THIS" : "custom (good)"}`);
});
