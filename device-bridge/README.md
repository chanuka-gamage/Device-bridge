# Device Bridge — personal phone/computer tracker

Three pieces, all in this folder:

```
backend/       Node.js server. Phone posts reports here; desktop app connects live.
android-app/   Android Studio project. Foreground service reports GPS + battery.
desktop-app/   Electron app. Live map + status dashboard.
```

Data flow: **Phone → Backend (internet) → Desktop app (WebSocket, live push)**

## 1. Backend

This needs to run somewhere reachable from your phone over the internet — a small
cloud VM or a free-tier host (Railway, Render, Fly.io all work fine for this).

```bash
cd backend
npm install
DEVICE_TOKEN="pick-a-long-random-secret" npm start
```

Deploying (example: Render/Railway):
1. Push this `backend/` folder to a new GitHub repo (or use their CLI).
2. Create a new Web Service pointing at it, build command `npm install`, start command `npm start`.
3. Set an environment variable `DEVICE_TOKEN` to a long random string — this is your
   shared secret. Anyone with it can post fake locations or read your real one, so
   treat it like a password.
4. Note the public URL it gives you (e.g. `https://your-app.onrender.com`) — you'll
   enter this in both the phone app and the desktop app.

## 2. Android app

Open `android-app/` in Android Studio (File → Open, pick the folder). Let Gradle sync
— it'll download the dependencies (Play Services Location, OkHttp).

Before installing on your phone:
- Build → Build Bundle(s)/APK(s) → Build APK, or just Run ▶ with your phone plugged
  in via USB (enable Developer Options → USB debugging on the phone first).

After installing:
1. Open the app, enter your backend URL and the `DEVICE_TOKEN` you picked, and a name
   like "My Phone".
2. Tap **Start**. Grant location permission when prompted.
3. Android will also ask for background location — choose **"Allow all the time"**,
   otherwise tracking stops as soon as you leave the app.
4. A persistent notification ("Sharing location with your dashboard") means it's
   running. It reports once a minute by default (see `REPORT_INTERVAL_MS` in
   `TrackingService.kt` if you want it more/less frequent — shorter intervals drain
   battery faster).

## 3. Desktop app

```bash
cd desktop-app
npm install
npm start
```

In the window that opens, enter the same backend URL and token, click **Connect**.
Your phone should appear on the map within a minute (or immediately if you have the
phone app open and moving). The sidebar shows battery %, charging status, GPS
accuracy, and last-update time per device.

## Notes on this being genuinely personal-use

- The shared token is the only thing standing between "your dashboard" and "anyone's
  dashboard" — keep it private, don't commit it to a public repo, rotate it if you
  ever suspect it leaked.
- This only tracks devices where you've installed the app and logged in with your own
  token — it has no way to track anything else.
- Consider adding HTTPS (most hosts above give you this for free) so the token and
  location data aren't sent in plaintext.
- If you want more history than the in-memory store keeps, swap the backend's
  in-memory `devices` object for a small SQLite file — happy to add that if you want it.
