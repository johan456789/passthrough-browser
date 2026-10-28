# passthrough-browser

## What it is

Waydroid runs Android in an LXC container on a Linux desktop. passthrough-browser makes links
opened inside Android open in the **host's** default browser instead. It has two parts: a tiny
Go HTTP server on the host (stdlib only) that runs `xdg-open <url>`, and an Android app that
registers as a browser, forwards each link to that server, and finishes without showing any UI.

```
Android app ──VIEW http(s)://…──▶ LinkActivity (no UI)
   │  gateway = default route of active network (e.g. 192.168.240.1)
   └─ POST http://<gateway>:<port>/open {"url": "..."} ──▶ Go server on host ──▶ xdg-open URL
```

For the full design (API contract, security decisions, test plan) see [docs/DESIGN.md](docs/DESIGN.md).

## How it works

The app finds the host by reading the default IPv4 gateway of the active network
(`ConnectivityManager`), falling back to `192.168.240.1`. It POSTs the URL as JSON to
`http://<gateway>:7157/open`. The server binds only to the Waydroid bridge IP, accepts only
clients inside `-allow-cidr`, validates the URL and starts `xdg-open` with it as a single
argument. Default port: **7157**.

| Method / path | Success | Errors |
|---|---|---|
| `GET /health` (also `HEAD`) | 200 `{"ok":true,"name":"passthrough-browser","version":"…","client":"<ip>"}` | 405 |
| `POST /open` body `{"url":"https://…"}`, `Content-Type: application/json` | 200 `{"ok":true}` (opener started) | 400 `bad_request`, 403 `forbidden`, 404 `not_found`, 405 `method_not_allowed`, 413 `body_too_large`, 415 `unsupported_media_type`, 422 `invalid_url`, 429 `rate_limited`, 500 `open_failed` |

Error bodies: `{"ok":false,"error":"<code>","message":"<text>"}`.

## Install the server

```sh
cd server
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o passthrough-browser .
install -Dm755 passthrough-browser ~/.local/bin/passthrough-browser
install -Dm644 systemd/passthrough-browser.service ~/.config/systemd/user/passthrough-browser.service
systemctl --user daemon-reload
systemctl --user enable --now passthrough-browser.service
systemctl --user status passthrough-browser
journalctl --user -u passthrough-browser -f
```

Server flags:

| Flag | Default | Meaning |
|---|---|---|
| `-addr` | `192.168.240.1` | IP to bind (must be a valid IP; otherwise exit 2) |
| `-port` | `7157` | TCP port, 1–65535 |
| `-allow-cidr` | `192.168.240.0/24` | Only clients in this prefix are served |
| `-retry-interval` | `2s` | Delay between bind attempts while the address is unavailable (must be > 0) |
| `-opener` | `xdg-open` | Program run with the URL as its single argument |
| `-verbose` | `false` | Log full URLs (default logs only scheme and host) |
| `-version` | `false` | Print `passthrough-browser <version>` and exit |

(or `make install-server`). The server waits for `waydroid0` automatically: while the bridge
address does not exist it retries the bind every 2 s (logging at most once a minute), so it can
be started before Waydroid.

## Build and install the app

Prerequisites: JDK 17 or 21 (JDK 25 is too new for Gradle 8.9), Android SDK with
`platforms;android-35` and `build-tools;35.0.0` (`sdkmanager "platforms;android-35" "build-tools;35.0.0"`),
`ANDROID_HOME` set (or `android/local.properties` with `sdk.dir=...`). The Gradle wrapper
(8.9) is committed.

```sh
cd android
./gradlew assembleDebug
waydroid app install app/build/outputs/apk/debug/app-debug.apk
```

Updating: run the same command (same signing key). Uninstall:
`waydroid app remove com.thyu.passthroughbrowser`.

## Set as default browser

- Open the app → **Set as default browser**; or
- Waydroid Settings → Apps → Default apps → Browser app → Passthrough Browser; or
- `sudo waydroid shell -- cmd role add-role-holder android.app.role.BROWSER com.thyu.passthroughbrowser`

## Changing the port

Server: edit `ExecStart` `-port N` in the unit (or `systemctl --user edit passthrough-browser`
with an override that clears and resets `ExecStart=`), then `systemctl --user daemon-reload`
and `systemctl --user restart passthrough-browser`. App: enter N → Save → Test connection.
Both must match. For a non-default Waydroid subnet, also change `-addr` and `-allow-cidr`.

## Security notes

- Binds only to the bridge IP; only clients in `-allow-cidr` are served (others get 403).
- http/https only; the URL is used verbatim as one argv entry of `xdg-open`, no shell.
- Request body ≤ 16 KiB, URL ≤ 8 KiB; server timeouts; global rate limit 5 burst / 1 per second.
- No authentication: any app inside Waydroid can make the host open http(s) URLs. That is the
  feature (equivalent to tapping a link), but be aware of it.
- Cleartext: the app permits cleartext HTTP in its base network config and enforces the
  destination in code. A `<domain-config>` for `192.168.240.1` would be tighter but would
  silently break if Waydroid's subnet changes, which the dynamic gateway discovery supports.
  The app never fetches forwarded links itself (it only sends them as JSON), and
  `ServerClient` refuses any host that is not a private (site-local) IPv4 address
  (10/8, 172.16/12, 192.168/16).
- The systemd unit deliberately has no sandboxing directives (`NoNewPrivileges`,
  `ProtectHome`, `PrivateTmp`, …): the launched browser inherits them, which breaks
  Chromium/Flatpak sandboxes and profile access.

## Troubleshooting

- **Server log says it is retrying the bind:** Waydroid is not running; check
  `ip addr show waydroid0`.
- **Test connection says "can't reach":** is the server running? The host firewall must allow
  TCP 7157 on `waydroid0`, e.g. `sudo ufw allow in on waydroid0 to any port 7157 proto tcp` or
  `sudo firewall-cmd --zone=trusted --add-interface=waydroid0`.
- **403:** the container IP is outside `-allow-cidr`.
- **Server returns 200 but nothing opens:** the service lacks `DISPLAY` / `WAYLAND_DISPLAY` /
  `DBUS_SESSION_BUS_ADDRESS`. Run `systemctl --user show-environment`; fix with
  `systemctl --user import-environment DISPLAY WAYLAND_DISPLAY DBUS_SESSION_BUS_ADDRESS XDG_CURRENT_DESKTOP`
  in session autostart. For window managers without `graphical-session.target`, change
  `WantedBy=` to `default.target` and import the environment. Check
  `xdg-settings get default-web-browser`.
- **App not in the "Browser app" list:** reinstall; verify with
  `sudo waydroid shell -- cmd package query-activities -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d https://x`.
- **Gateway shows "(fallback)":** the network is not up yet; reopen the app.

## License

MIT — see [LICENSE](LICENSE).
