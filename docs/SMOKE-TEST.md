# WiFiQueue — manual smoke-test checklist (Redmi A3)

App under test: `WiFiQueue-debug.apk` — `com.wifiqueue.app` v1.0 (versionCode 1),
minSdk 24, targetSdk 34, debug-signed, no native libs.
Intended device: Redmi A3, Android 13/14 (Go), arm64.

Every step below lists what the code actually does, so a mismatch is a real finding.
Record **PASS / FAIL / BLOCKED** per row and keep the evidence notes.

---

## 0. Setup (5 minutes, do this first)

| # | Action | Expected |
|---|---|---|
| 0.1 | Settings → About phone → tap HyperOS/MIUI version 7× | "You are now a developer" toast |
| 0.2 | Settings → Additional settings → Developer options → **Install via USB** = on (sign in to Mi account if asked) | Toggle stays on |
| 0.3 | Battery → App battery saver → WiFiQueue → **No restrictions** | App not killed in background |
| 0.4 | Install `WiFiQueue-debug.apk` (Files app, or `adb install -r WiFiQueue-debug.apk`) | App "WiFiQueue" appears in launcher with the Wi-Fi icon |
| 0.5 | Open the app. Android 13+ shows a notification permission dialog | Dialog appears once. Choose **Allow** for the full run; repeat 0.5 with **Don't allow** at the end (test D7) |
| 0.6 | Connect to a Wi-Fi network, mobile data OFF | — |
| 0.7 | Optional but recommended: `adb logcat -c`, then `adb logcat -s QueueManager QueueSequencer DownloadReceiver` in a second terminal | Empty buffer ready for evidence |

**Test URLs (use these exact ones so results are comparable):**

| Purpose | URL |
|---|---|
| Small file, ~1 MB | `http://ipv4.download.thinkbroadband.com/1MB.zip` |
| Medium file, ~10 MB | `https://proof.ovh.net/files/10Mb.dat` |
| Large file, ~100 MB (for Wi-Fi/pause tests) | `https://proof.ovh.net/files/100Mb.dat` |
| Guaranteed 404 | `https://proof.ovh.net/files/this-does-not-exist.bin` |
| HTML page, not a file | `https://example.com` |
| Not a URL at all | `not a url` |
| Slow/stalling host (optional) | `https://speed.hetzner.de/1GB.bin` |

---

## A. Add to queue

| # | Action | Expected result |
|---|---|---|
| A1 | **Home** tab, Wi-Fi on | Card shows Wi-Fi icon on + "**Wi‑Fi connected**" / "Ready to download" |
| A2 | Turn Wi-Fi off, return to Home | Card shows Wi-Fi-off icon + "**No Wi‑Fi**" / "Waiting for Wi‑Fi to start downloads" |
| A3 | Leave both fields empty | "Add to queue" button is **disabled** |
| A4 | Fill only the name | Button stays disabled (both fields required) |
| A5 | Name `Test 1MB`, URL = 1 MB test URL → tap **Add to queue** (Wi-Fi ON) | Fields clear, app **switches to the Queue tab automatically**, one card appears |
| A6 | Inspect the new card | Status label "**Waiting to be queued**", no progress bar, button reads "**Queue**" |
| A7 | Wait ~10 s without tapping anything | Item stays WAITING — adding never auto-starts (only queuing does) |
| A8 | Wi-Fi **off**, add a second item, tap **Queue** on it | Item becomes QUEUED ("Queued – waiting to start") and **stays** there; nothing downloads |
| A9 | Turn Wi-Fi on (app in foreground) and wait ≤5 s | QUEUED item flips to **DOWNLOADING** on its own (connectivity callback → queue pass) |

---

## B. Sequential queue

| # | Action | Expected result |
|---|---|---|
| B1 | With the 100 MB file downloading, add a third item and **Queue** it | At most **one** card shows "Downloading..." and a progress bar; the other sits at "Queued – waiting to start" |
| B2 | Watch both cards for the download duration | No second progress bar ever appears in parallel; queued item shows 0% and no byte counters |
| B3 | When the first completes | First card **disappears from Queue** and shows up in **History**; the queued item starts automatically within ~2.5 s (UI tick) |
| B4 | Tap the button on the QUEUED item ("Queued") | It returns to "Waiting to be queued" (un-queue works); button becomes "Queue" |
| B5 | Deliverable check | If two downloads ever run at once → **FAIL** (sequential invariant broken) |

---

## C. Wi-Fi loss and return

| # | Action | Expected result |
|---|---|---|
| C1 | Start the 100 MB download; wait until ≥5% | Progress bar, "%" and "downloaded / total" bytes advance every ~2.5 s |
| C2 | **Turn Wi-Fi OFF** (leave mobile data ON) | Within ~2.5 s: download stops, card returns to "**Queued – waiting to start**", progress back to **0%**, red text "**Wi-Fi was disconnected. It will restart automatically when Wi-Fi returns.**" |
| C3 | Check that mobile data was NOT used: Settings → Network → Data usage (or SIM data counter) while waiting | No meaningful data consumed by WiFiQueue — the request is pinned to `NETWORK_WIFI` |
| C4 | Check the notification shade | The progress notification for that download is gone |
| C5 | Check partial file was discarded: Files → Android/data/com.wifiqueue.app/files/Download | No leftover partial file for the cancelled transfer |
| C6 | **Turn Wi-Fi back ON**, app in foreground (Auto resume on — the default) | QUEUED item restarts **automatically** within ~5 s, and starts from **0 bytes** ("downloaded" restarts low, total unchanged) — this is the documented DownloadManager limitation, not a bug |
| C7 | Repeat C1–C6 but with the app **backgrounded** (press Home) before dropping Wi-Fi | Same end state expected, but restart may be delayed: background reconciliation is the 15-minute WorkManager pass, so allow up to ~15 min (Redmi battery saver can defer it further) — delay here is expected behaviour, not a FAIL |
| C8 | Wi-Fi off, tap **Resume/Queue** manually on the queued item | Stays QUEUED with no error; nothing starts until Wi-Fi returns |

---

## D. Pause / resume / retry / cancel

| # | Action | Expected result |
|---|---|---|
| D1 | Downloading an item, tap **Pause** | Card becomes "**Paused**", progress bar disappears, 0 bytes, button reads "**Resume**"; notification cleared; partial file deleted (check the Download folder) |
| D2 | Confirm nothing resumes on its own | Stays PAUSED while paused — user pause is never auto-restarted, even across Wi-Fi loss/return and app restart |
| D3 | Tap **Resume** | Card shows red note "**Restarted from the beginning: the previous partial data could not be continued.**" only if progress had been >0; item becomes QUEUED then DOWNLOADING and restarts from 0 bytes |
| D4 | While downloading, tap **Pause** then wait 2 min with Wi-Fi on | Still PAUSED; no ghost DownloadManager job keeps running (no bytes moving on the card) |
| D5 | Let an item **fail** (404 URL, see E1), then tap **Retry** | Card returns to QUEUED → DOWNLOADING; the old red error text disappears while retrying and the file restarts from 0 |
| D6 | Tap the **trash/delete** icon on a DOWNLOADING item | Card vanishes immediately; transfer stops; partial file removed; **previously completed files in History stay on disk** (verify: other downloads' files still present) |
| D7 | Force-stop the app (Settings → Apps → WiFiQueue → Force stop) while a download runs, then relaunch | The system download keeps running; on relaunch the item is reconciled and shows either continuing progress or COMPLETED in History (one immediate worker pass runs at startup) |
| D8 | Rotate the phone / leave and re-enter the app during a download | Selection and queue survive; the notification-permission dialog does **not** reappear |

---

## E. Failure messages (the honesty checks)

Each case must show a **specific** message in red on the card, status "**Failed – tap Retry**", and a **Retry** button.

| # | Action | Expected message |
|---|---|---|
| E1 | Add + Queue `https://proof.ovh.net/files/this-does-not-exist.bin` | "**The server returned HTTP 404 and did not deliver the file.**" |
| E2 | Add + Queue `not a url` | "**This URL is not a valid direct download link.**" (rejected when the request is built) |
| E3 | Add + Queue `https://example.com` (HTML page) | One of: "The server redirected too many times. The URL may not be a direct file." / "The server responded in an unexpected way." / "The server returned HTTP 4xx/5xx…" — any of these is correct, as long as it is not the generic fallback |
| E4 | Queue a URL whose host does not exist, e.g. `https://no-such-host-9f3.invalid/file.zip` | A specific failure inside ~30 s or a pause reason message; must not hang at "Downloading..." forever (see F1/F2) |
| E5 | Failure bookkeeping | Failed item shows **0 bytes**, no progress bar, and does **not** get retried automatically; the queue moves on to the next QUEUED item |
| E6 | Fill internal storage to <50 MB free (or use a huge file on a full-ish device), then Queue a download whose size is not yet known | "**Not enough free storage space to start this download.**" — refused before any request is created |
| E7 | While a normal download runs, read the grey diagnostic line (if shown) | Only appears for unusual Android states, e.g. "Android is waiting for a usable network (pause reason 2: waiting for network)"; a healthy transfer shows **no** grey diagnostic line |
| E8 | Signal-loss test: start the 100 MB download, then enable Airplane mode with Wi-Fi also off | Card moves to a pause reason message or the Wi-Fi-loss message — never a fake 0% "Downloading..." that lasts forever |

---

## F. Stall recovery (needs patience; the timings are in the code)

| # | Action | Expected result |
|---|---|---|
| F1 | Queue a URL that Android accepts but never starts (e.g. a host that hangs; `https://speed.hetzner.de/1GB.bin` on a throttled connection is a reasonable proxy) | After **~90 s** with 0 bytes pending, the item flips to **Paused** with "**Android transferred no data for ~90s (…, reason N). … Tap Resume to retry.**" and the queue continues with the next item |
| F2 | Start a large download, then suspend the server (toggle Wi-Fi off/on quickly, or use a flaky hotspot) so bytes stop mid-transfer | If paused/pending with no bytes for **~10 min**, the same stall message appears, partial data is discarded, and the queue is released |
| F3 | After F1/F2 | The record is **kept** (not deleted), state PAUSED, Resume available; the next queued item starts |

> F1/F2 are long waits — mark them BLOCKED rather than FAIL if you cannot run them in this session.

---

## G. Completion, history, storage and notifications

| # | Action | Expected result |
|---|---|---|
| G1 | Download the 1 MB file to completion | Status becomes COMPLETED, progress 100%, a completion notification appears (if permission granted), card moves to **History** showing "Completed successfully" |
| G2 | Verify the file on disk | `Android/data/com.wifiqueue.app/files/Download/<your name>` exists with a plausible size (≈1 MB). Use the Files app or `adb shell ls -l /sdcard/Android/data/com.wifiqueue.app/files/Download` |
| G3 | Open the downloaded file from the Files app | It is intact (for the zip, it opens/extracts; the server may deliver an HTML error page — check size vs Content-Length) |
| G4 | Add two items with the **same** name, download both | Second file is saved as "`Name (1).ext`" — never overwrites the first |
| G5 | Use a name containing `/ : * ? "` (e.g. `Movie: Part 1/2`) | Illegal characters are replaced with `_`; download still succeeds |
| G6 | On the History card | Title and the **full URL** are shown (expected: history has no delete/open action yet) |
| G7 | Look at **Download Manager** system app / notification shade during the test | The system's own download notification also appears (VISIBILITY_VISIBLE_NOTIFY_COMPLETED) — duplicate-ish notifications are expected, not a bug |

---

## H. Settings (persisted and applied)

| # | Action | Expected result |
|---|---|---|
| H1 | Toggle "Wi‑Fi only mode" off, leave the tab, force-stop the app, come back | Switch is still **off** (persisted in DataStore) and the description reads "Downloads may continue on mobile data." |
| H2 | Wi‑Fi only **off**, mobile data on, Wi‑Fi off, Queue a download | It actually downloads over mobile data — progress advances with Wi‑Fi disconnected |
| H3 | Wi‑Fi only **on**, start a download, then switch Wi‑Fi off | Active download stops and the card shows the Wi‑Fi-loss message (proves the toggle changes engine behaviour, not just the switch) |
| H4 | "Auto resume" **off**, start a download, switch Wi‑Fi off | Item becomes **Paused** with "Wi-Fi was disconnected and auto resume is off. Tap Resume when Wi-Fi is back." and does **not** restart when Wi-Fi returns |
| H5 | With auto resume off, tap **Resume** | Starts normally — the setting only blocks the automatic restart |
| H6 | "Download overnight" **on** during the day (between 06:00 and 22:00), then Queue an item | Item stays QUEUED with the grey note "Download overnight is on: this starts between 22:00 and 06:00…" and does not start; toggling the setting off starts it within ~2.5 s |
| H7 | "Download overnight" on, set the phone clock inside 22:00–06:00, Queue an item | Starts immediately |
| H8 | With overnight on, a download running when 06:00 arrives | It keeps running — only the *start* of a queued item is gated |
| H9 | Settings → Storage overview | **Real** Used / Available / Total for the download volume (cross-check against Settings → Storage), "Queue requires" = sum of known sizes of unfinished items, "(+N with size not reported yet)" when some sizes are unknown, and Status sufficient/not enough |
| H10 | Queue a ~10 MB file, then reopen Settings | "Queue requires" grows by roughly that size once DownloadManager has reported it |
| H11 | Decline the notification permission at 0.5 | Downloads still work and every setting above still applies; only notifications are missing |

---

## I. Architecture / Redmi A3 compatibility

| # | Check | Expected |
|---|---|---|
| I1 | `adb shell getprop ro.product.cpu.abi` | `arm64-v8a` — and the APK contains **no** native libraries, so ABI cannot be a problem |
| I2 | `adb shell getprop ro.build.version.sdk` | 33 or 34 (Android 13/14); app's minSdk 24 / targetSdk 34 is compatible |
| I3 | App launch time on this low-end device | First launch < ~5 s, no ANR. Compose + Room + WorkManager only |
| I4 | Memory | No OOM/ANR while a 100 MB download runs, screen off |
| I5 | Logcat during the run: `adb logcat -s QueueManager` | Lines like `dm=<id> item=<id> status=RUNNING(4) reason=0 downloaded=… total=… src=https://host dst=…` and `Enqueued dm=… for item …`; **URLs appear only as `scheme://host`** (no paths/query tokens) — check this for the privacy claim |

---

## J. Evidence to capture for each FAIL

1. Screenshot of the Queue card (status label + red text + button).
2. `adb logcat -d -s QueueManager QueueSequencer DownloadReceiver > wifiq-log.txt`.
3. `adb shell ls -l /sdcard/Android/data/com.wifiqueue.app/files/Download`.
4. One line of context: Wi-Fi state, mobile data state, app foreground/background, elapsed time.

Minimum useful run if short on time: **A5 → A9 → B1 → C2 → C6 → D1 → D3 → D6 → E1 → E2 → G1 → G2 → G7**. That covers add-to-queue, sequencing, Wi-Fi loss/return, pause/resume, delete, two failure classes, completion and on-disk verification.
