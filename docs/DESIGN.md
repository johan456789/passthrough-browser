# passthrough-browser — Design (v0.1.0)

> This project was written independently from a requirements-only specification (clean-room),
> without reference to any other implementation of the same idea.

## 1. Overview

Waydroid runs Android in an LXC container on a Linux desktop. Links opened inside Android
should open in the **host's** default browser instead.

Two components:

1. **Host server** (`server/`, Go, standard library only): a small HTTP server bound to the
   Waydroid bridge IP (default `192.168.240.1`, port `7157`). On `POST /open` with a valid
   http/https URL it runs `xdg-open <url>` (no shell).
2. **Android app** (`android/`, Kotlin, no third-party runtime libraries): registers as a
   browser. When it receives a link it discovers the host as the default IPv4 gateway, POSTs
   the URL to the server, and finishes without UI (a Toast is shown only on failure). When
   opened from the launcher it shows a single settings/help screen.

```
Android app ──VIEW http(s)://…──▶ LinkActivity (no UI)
   │  gateway = default route of active network (e.g. 192.168.240.1)
   └─ POST http://<gateway>:<port>/open {"url": "..."} ──▶ Go server on host ──▶ xdg-open URL
```

Non-goals (v0.1.0): authentication tokens, TLS, share-sheet (`ACTION_SEND`) handling, CI,
IPv6, multiple hosts, Play Store distribution.

## 2. Repository layout

```
.
├── LICENSE  README.md  Makefile  .gitignore
├── docs/DESIGN.md
├── server/
│   ├── go.mod                 module github.com/johan456789/passthrough-browser/server
│   ├── main.go                flags, signal handling, wiring
│   ├── server.go              handlers, client-allow middleware, JSON responses
│   ├── urlcheck.go            validateURL
│   ├── ratelimit.go           token bucket
│   ├── listen.go              listenWithRetry
│   ├── opener.go              Opener interface + exec implementation
│   ├── *_test.go
│   └── systemd/passthrough-browser.service
└── android/
    ├── settings.gradle.kts  build.gradle.kts  gradle.properties
    ├── gradlew  gradlew.bat  gradle/wrapper/…
    └── app/
        ├── build.gradle.kts  proguard-rules.pro
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml
            │   ├── java/com/thyu/passthroughbrowser/
            │   │   LinkActivity  MainActivity  ServerClient  ServerResponses
            │   │   GatewayResolver  Prefs  Validation (.kt)
            │   └── res/  (layout, values, values-night, xml, drawable, mipmap-anydpi)
            └── test/java/com/thyu/passthroughbrowser/  ValidationTest  ServerClientTest
```

## 3. Host server

### 3.1 Build

Go ≥ 1.22, stdlib only (no `require` lines).
`CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o passthrough-browser .` produces a single
static binary. `version` (default `0.1.0`) can be overridden with `-ldflags "-X main.version=…"`.

### 3.2 Flags

| Flag | Default | Meaning |
|---|---|---|
| `-addr` | `192.168.240.1` | IP to bind; must parse as an IP, otherwise exit 2 |
| `-port` | `7157` | TCP port, 1–65535, otherwise exit 2 |
| `-allow-cidr` | `192.168.240.0/24` | Only clients in this prefix are served; invalid ⇒ exit 2 |
| `-retry-interval` | `2s` | Delay between bind attempts while the address is unavailable; must be > 0 |
| `-opener` | `xdg-open` | Program run with the URL as its single argument; if not found at startup a warning is logged and every open then fails with 500 |
| `-verbose` | `false` | Log full URLs (default logs only scheme and host) |
| `-version` | `false` | Print `passthrough-browser <version>` and exit 0 |

If `-addr` is outside `-allow-cidr` a warning is logged (not fatal).

Startup: validate flags → log `starting` (version, addr, port, allow) → install SIGINT/SIGTERM
handling → `listenWithRetry` → serve. On a signal the server shuts down gracefully (5 s limit)
and exits 0; a serve error other than `http.ErrServerClosed` exits 1. Logging uses `log/slog`
text output on stderr (captured by journald).

### 3.3 Bind retry

`listenWithRetry(ctx, listen, network, address, interval, log)` loops until the listen succeeds
or the context is cancelled. All errors are retried: `EADDRNOTAVAIL` (bridge not up yet) is the
expected case, `EADDRINUSE` is retried so restart races resolve themselves. A warning is logged
on the first failure and then at most once per 60 s. The server therefore can be started before
Waydroid and binds within one retry interval after the bridge appears, without a restart.

### 3.4 HTTP API

Base URL: `http://<addr>:<port>`. All responses carry
`Content-Type: application/json; charset=utf-8`, `X-Content-Type-Options: nosniff`,
`Cache-Control: no-store`, and are single-line JSON objects.

Error shape (all non-2xx): `{"ok":false,"error":"<code>","message":"<human readable>"}`.

**`GET /health`** (also `HEAD`) → 200
`{"ok":true,"name":"passthrough-browser","version":"0.1.0","client":"<requester ip>"}`.
Other methods → 405 with `Allow: GET, HEAD`.

**`POST /open`**
- `Content-Type: application/json` (parameters such as charset allowed; media type compared
  case-insensitively).
- Body `{"url":"https://example.com/path?q=1"}`; unknown fields ignored; exactly one JSON value
  (trailing data ⇒ 400); max 16 KiB.

| Status | `error` | When |
|---|---|---|
| 200 | — | `{"ok":true}`: the opener process **started** |
| 400 | `bad_request` | malformed JSON, missing/empty/non-string `url`, trailing data |
| 403 | `forbidden` | client IP outside `-allow-cidr` (every path) |
| 404 | `not_found` | any path other than `/health`, `/open` |
| 405 | `method_not_allowed` | wrong method; `Allow` header set |
| 413 | `body_too_large` | body over 16 KiB |
| 415 | `unsupported_media_type` | missing or non-JSON Content-Type |
| 422 | `invalid_url` | URL fails validation; message states the rule |
| 429 | `rate_limited` | token bucket empty; `Retry-After: 1` |
| 500 | `open_failed` | opener process could not be started |

Check order for `/open`: client subnet (middleware) → method → content type → body size/JSON →
URL validation → rate limit → open. The rate limit comes after validation so garbage does not
consume tokens.

Routing uses `http.NewServeMux()` with `/health`, `/open` and a catch-all that returns JSON
404; method checks live inside handlers so 405 bodies are JSON as well.

### 3.5 URL validation

Rules, first failure wins:
1. Length 1..8192 bytes.
2. Valid UTF-8.
3. No byte ≤ 0x20 and no 0x7f (no spaces, control characters or NUL). Nothing is trimmed.
4. Case-insensitive prefix `http://` or `https://` on the raw string; additionally an explicit
   check that the string does not start with `-` (defence against option injection).
5. `url.Parse` succeeds, scheme is http/https, not opaque, non-empty hostname.

The validated **raw string** (not a re-serialised one) is passed to the opener so encoding is
never altered.

### 3.6 Hardening

`http.Server` limits: `ReadHeaderTimeout` 5 s, `ReadTimeout` 10 s, `WriteTimeout` 10 s,
`IdleTimeout` 30 s, `MaxHeaderBytes` 8 KiB.

Client check: the peer address is parsed from `RemoteAddr`, IPv4-mapped IPv6 is unmapped, and
the peer must be inside `-allow-cidr`, otherwise 403 (with a warning log). `X-Forwarded-For` and
other headers are never consulted. A request from the host itself to the bridge IP has the
bridge IP as its source, which is inside the default CIDR, so `curl` on the host works.

### 3.7 Rate limiting

Global (not per-client) token bucket, burst 5, refill 1 token/s. Only `/open` consumes tokens.
The clock is injectable for tests.

### 3.8 Opener

`exec.Command(program, url)`: exactly two argv entries, never a shell, no `--` (xdg-open
treats unknown options as errors; safety comes from rule 3.5.4). Environment is inherited
(needs `DISPLAY`/`WAYLAND_DISPLAY`/`DBUS_SESSION_BUS_ADDRESS`). The process is started, then
reaped in a goroutine; it is never killed on a timeout because some xdg-open backends stay
attached to the browser process. Success is logged as `open client=… url=…` (full URL only with
`-verbose`).

### 3.9 systemd user unit

Runs `%h/.local/bin/passthrough-browser -addr 192.168.240.1 -port 7157`, `Restart=on-failure`,
`WantedBy=graphical-session.target`. There are deliberately **no** sandboxing directives
(`NoNewPrivileges`, `ProtectHome`, `PrivateTmp`, …): the launched browser inherits them, which
breaks Chromium/Flatpak sandboxes and profile access.

## 4. Android app

### 4.1 Toolchain

Gradle 8.9 (wrapper), Android Gradle Plugin 8.7.3, Kotlin 2.0.21, JDK 17 or 21 to run Gradle
(newer JDKs are not supported by this Gradle version), bytecode target 17, compileSdk/targetSdk
35, minSdk 26, build-tools 35.0.0. Runtime dependencies: none besides the Kotlin standard
library (no AndroidX, no coroutines); activities extend `android.app.Activity` and background
work uses plain threads. Test dependency: JUnit 4.13.2. No native code, so the APK is universal.
The release build type is signed with the debug key (personal sideload build).

Identity: namespace and applicationId `com.thyu.passthroughbrowser`.

### 4.2 Manifest

- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`.
- `MainActivity`: exported launcher activity, `singleTask`.
- `LinkActivity`: exported, `Theme.NoDisplay`, `excludeFromRecents`, `noHistory`, empty
  `taskAffinity`. Intent filters: `VIEW` + `DEFAULT` + `BROWSABLE` for `http`/`https` with no
  host restriction, and a second filter adding the MIME types `text/html`, `text/plain`,
  `application/xhtml+xml`. The first filter is what makes the app eligible for
  `android.app.role.BROWSER` (Android 10+); do not add `android:host`.

### 4.3 Network security: cleartext decision

The base network-security config **permits cleartext**, and the destination restriction is
enforced in code. The host IP is discovered dynamically (default gateway), so it cannot be
listed statically; a `<domain-config>` for `192.168.240.1` would be tighter but would silently
break if the user changes Waydroid's subnet, which dynamic discovery exists to support. The app
never fetches forwarded links itself (it only sends them as JSON), so the global allowance does
not expose user browsing. The code-level guard: `ServerClient` refuses any host that is not an
`Inet4Address` with `isSiteLocalAddress` (10/8, 172.16/12, 192.168/16), and the app has exactly
one network code path.

### 4.4 Components

Package `com.thyu.passthroughbrowser`.

- **`Validation.kt`** (pure Kotlin, JVM-testable): `parsePort` (1..65535, trims whitespace),
  `isForwardableUrl` (http/https prefix, non-empty host, ≤ 8192 UTF-8 **bytes**, no chars ≤ 0x20
  or 0x7f; mirrors the server so bad intents fail fast, the server stays authoritative),
  `jsonString`, `selectGateway` (first non-null IPv4, not any-local/loopback, site-local),
  `isAllowedServerHost`. Constants: default port 7157, fallback host `192.168.240.1`.
- **`Prefs`**: `SharedPreferences` holding the port; invalid stored values fall back to 7157.
- **`GatewayResolver`**: inspects the active network and then all networks via
  `ConnectivityManager`, takes the gateways of default routes through `selectGateway`, and
  otherwise falls back to `192.168.240.1` (`discovered = false`). Cheap and synchronous.
- **`ServerClient`** (`java.net` only): `health()` (GET `/health`) and `open(url)` (POST `/open`
  with `{"url":…}`) via `HttpURLConnection`: no redirects, no caches, 2 s connect / 3 s read
  timeouts, `Accept: application/json`, response read capped at 64 KiB. Refuses non-allowed
  hosts and invalid ports. Never throws; returns `CallResult.Http(status, body)` or
  `CallResult.Failure(exception)`. Must not be called on the main thread.
- **`ServerResponses`**: maps results to user-facing text (all strings in `strings.xml`),
  prefixed with the app name for Toasts:

  | Result | Message |
  |---|---|
  | Connect / timeout / no-route failure | Can't reach passthrough-browser at `host:port`. Is the host server running? |
  | Other failure | Couldn't send link to `host:port`: `<message or class>` |
  | HTTP 403 | Host server at `host:port` refused this device (not in allowed subnet). |
  | HTTP 422 | Host refused the link: `<message>` |
  | HTTP 429 | Too many links at once; try again. |
  | Other non-200 | Host server error (HTTP `<status>`): `<message or body excerpt ≤ 120>` |
  | 200 on `/health` with wrong name/JSON | Something answered at `host:port` but it is not passthrough-browser. |

- **`LinkActivity`**: reads the `VIEW` URL; if it is missing or not forwardable, shows a short
  "unsupported link" Toast and finishes. Otherwise resolves gateway and port, starts a
  `pb-forward` thread that POSTs the URL (Toast only on failure, via the main-thread handler),
  and finishes immediately (zero-duration close transition, set before `finish()` on API 34+).
  There is no fallback `startActivity`, so no loops when this app is the default browser.
- **`MainActivity`**: single settings/help screen (below).

### 4.5 Launcher screen

The action bar already shows the app name, so the body does not repeat it (except in the
instruction path, where it is the item to choose). Top to bottom:

1. Explanation paragraph: what the app does and that the host must run the server.
2. Default-browser status line: `Default browser: YES` (green) or `Default browser: NO` (red).
   Only the YES/NO word is coloured and bold; colours come from `colors.xml` with lighter shades
   in `values-night`. Shown on API 29+ when the browser role is available; refreshed in
   `onResume` and after the role request returns.
3. Instruction path: `Settings > Apps > Default apps > Browser app > Passthrough Browser`
   (plain ASCII `>` separators, which align with the text in the default font).
4. **Set as default browser** button: on API 29+ requests the browser role via `RoleManager`
   (reports "Already the default browser" if held); on API 26–28 or if the request intent is
   unavailable it opens the default-apps settings, falling back to general settings.
5. Host line: `Host (default gateway): <ip>` or `Host: <ip> (fallback — gateway not detected)`,
   re-resolved on every resume.
6. Port field (numeric, max 5 digits, IME action Done saves), **Save** and **Test connection**.
   Save validates with `parsePort` (error "Enter a port between 1 and 65535"), persists, and
   reports "Saved port N". Test uses the field value without saving, runs `health()` on a
   background thread, and reports
   `OK: passthrough-browser <version> at <host:port> (server sees this device as <client>)`
   or `FAILED: <mapped failure message>` (same messages as the table in §4.4, without the
   Toast's app-name prefix). Only the leading word is styled: bold, green (`OK`) or red
   (`FAILED`), using the same colours as the YES/NO status above; the rest keeps the default
   text colour.
7. A result/status text (selectable) and a small version label.

Edge-to-edge (enforced by targetSdk 35 on Android 15+): on API 30+ the root view applies system
bar and IME insets as padding; on older versions `fitsSystemWindows` suffices.

Themes: light Material (dark action bar) by default, plain dark Material in night mode. The icon
is an adaptive vector ("open external" arrow on a blue background).

## 5. Tests

Server (`go test ./...`, loopback only, no real `xdg-open`): URL validation table (valid and
invalid cases including the 8192-byte boundary), every status code of the API, forbidden
clients, IPv4-mapped clients, opener failure, rate limiting, token-bucket burst/refill/cap, bind
retry success and cancellation, and the exec opener (`true` succeeds, a missing program fails).

Android (`./gradlew testDebugUnitTest`, plain JVM, JUnit 4): `parsePort`, `isForwardableUrl`
(including a multibyte URL over the byte limit), `jsonString`, `selectGateway`; `ServerClient`
against a local fake HTTP server (request shape and JSON escaping, 422 error body, health, a
closed port ⇒ `ConnectException`, default host guard rejecting loopback and public addresses,
read timeout ⇒ `SocketTimeoutException`). The fake server is a minimal `ServerSocket` responder,
because Android Gradle Plugin unit tests compile against `android.jar` only and the JDK's
`com.sun.net.httpserver` module does not resolve.

## 6. Manual test plan

### 6.1 Server

```sh
cd server && go vet ./... && go test ./... && CGO_ENABLED=0 go build -o passthrough-browser .
./passthrough-browser -verbose
B=http://192.168.240.1:7157

curl -i $B/health                                              # 200 JSON
curl -i -X POST $B/health                                      # 405
curl -i -X POST -H 'Content-Type: application/json' \
     -d '{"url":"https://example.com/?a=1&b=2"}' $B/open       # 200, page opens on host
# 422: {"url":"javascript:alert(1)"}, {"url":"file:///etc/passwd"}, {"url":"--help"}, {"url":"https://x/a b"}
curl -i -X POST -H 'Content-Type: text/plain' -d 'https://example.com' $B/open   # 415
curl -i -X POST -H 'Content-Type: application/json' -d '{"url":' $B/open         # 400
# a body over 16 KiB                                                            # 413
curl -i -X GET $B/open                                         # 405
curl -i $B/nope                                                # 404
for i in $(seq 8); do curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  -H 'Content-Type: application/json' -d '{"url":"https://example.com"}' $B/open; done  # 200×5 then 429
./passthrough-browser -allow-cidr 10.0.0.0/8 & curl -i $B/health   # 403
```

Bind retry: start the server while the bridge is absent (or use an address that is not local) ⇒
a warning is logged and retried; once the address exists the server logs `listening` without a
restart. SIGTERM exits cleanly within 5 s. `ss -ltn 'sport = :7157'` must show only the bridge
address, never `0.0.0.0`.

### 6.2 App (Waydroid)

```sh
cd android && ./gradlew assembleDebug testDebugUnitTest
waydroid app install app/build/outputs/apk/debug/app-debug.apk
sudo waydroid shell -- cmd role add-role-holder android.app.role.BROWSER com.thyu.passthroughbrowser
sudo waydroid shell -- cmd role get-role-holders android.app.role.BROWSER
sudo waydroid shell -- am start -a android.intent.action.VIEW -d 'https://example.com/?from=waydroid&x=1'
journalctl --user -u passthrough-browser -n 5      # "open client=192.168.240.x"
```

Expected: the URL opens in the host's default browser; nothing is visible in Waydroid and there
is no Recents entry. With the server stopped a link produces the "Can't reach…" Toast within
about 3 s.

UI checks: the launcher screen shows the status line (YES in green / NO in red), the gateway IP
(not "fallback"), field errors for `0`, `65536` and `abc`, the port persisted across restarts,
and Test connection reporting success or failure correctly. Changing the port on both sides
(e.g. 7158) keeps the system working. A UTF-8 URL such as `http://例え.jp/パス` opens on the host.

## 7. Security summary

- Binds only to the bridge IP; only clients inside `-allow-cidr` are served.
- http/https only; the URL is used verbatim as one argv entry, never via a shell; leading `-`
  is rejected.
- Body ≤ 16 KiB, URL ≤ 8 KiB, server timeouts, global rate limit (burst 5, 1/s).
- No authentication: any app inside Waydroid can make the host open http(s) URLs, which is the
  feature (equivalent to tapping a link).
- Cleartext allowance and its code-level guard: see §4.3.
- No systemd sandboxing, deliberately: see §3.9.
