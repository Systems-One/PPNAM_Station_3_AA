# Station 3 UI Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every Station 3 finding from the 2026-10-01 handheld UI audit (S3-01..S3-10 plus the static-consistency rows that apply to S3) and bring the app onto the audit's recommended cross-app standard (gear icon, M3 dialogs, Test & Apply settings, inactivity auto sign-out, Enter-submits, operator-facing errors, portrait lock).

**Architecture:** Station 3 is an XML + ViewBinding app (three activities: `LoginActivity`, `MainActivity` with the Master Batch panel, `SettingsActivity`) ported from Station 1. Fixes are ordered as the audit's §7 fix order: Tier 1 manifest/theme one-liners, then Tier 2 small code patterns (Enter handling, persisted PIN gate, error placement, Back dialog, error-string mapping), then Tier 3 behaviour work (inactivity sign-out copied from Station 1, Test & Apply settings, shared theme/dialog/switch/icon chrome). Every piece of pure logic (PIN gate, editor-action decision, login-error mapping, auto-logout parsing, inactivity timer) is a plain Kotlin class with a JUnit test; everything touching Views is verified on the emulator plus a compile check.

**Tech Stack:** Kotlin 2.0, AGP 8.4.2, compileSdk 35 / minSdk 26, Material Components 1.12.0 (`Theme.Material3`), AndroidX AppCompat/ConstraintLayout/DynamicAnimation, HiveMQ MQTT client, JUnit 4 JVM unit tests under `app/src/test` (no Robolectric, no instrumented UI tests — `app/src/androidTest` is empty).

**Spec:** `C:\Users\Jonathan\AppData\Local\Temp\claude\C--Dev-Clients-PPNAM\ba7a1680-4205-4b04-bcb6-1b1f23c94914\scratchpad\audit\CONSOLIDATED_REPORT.md` (§3 root causes, §4 findings register, §5 consistency matrix "Recommended standard", §6 keyboard matrix, §7 fix order), with `station3.md` (dynamic audit) and `static_consistency.md` (static audit) in the same folder.

## Global Constraints

- Repo root: `C:\Dev\Clients\PPNAM\Station 3\PPNAM_Station_3_AA`. Package `com.mitas.ppnam.station3aa`. Source root `app\src\main\java\com\mitas\ppnam\station3aa\`, resources `app\src\main\res\`, tests `app\src\test\java\com\mitas\ppnam\station3aa\`.
- Branch: `fix/ui-audit-2026-10-02` off `master`. Commit after every task with a conventional message. Never `git add -A`; add only the task's files. Every commit message ends with the two lines `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q`.
- Pre-existing dirty files at planning time: **none** (`git status --porcelain` was empty on `master` at `33804c2`). If Task 1's `git status` shows dirty files, record them in the task and do not touch them.
- Build: `.\gradlew.bat :app:assembleDebug --offline` from the repo root (drop `--offline` only if it fails on a missing dependency). APK: `app\build\outputs\apk\debug\app-debug.apk`. Unit tests: `.\gradlew.bat :app:testDebugUnitTest --offline`.
- Test infrastructure: JUnit 4 JVM tests only (`testImplementation(libs.junit)`, real `org.json`). There is NO Robolectric and NO instrumented/Espresso test in the repo, so every View-level change is verified by (a) the compile check above and (b) the documented manual emulator steps in the task. Pure logic must live in a plain Kotlin class with a JUnit test.
- Emulator: serial `emulator-5556`, adb `C:\Users\Jonathan\AppData\Local\Android\Sdk\platform-tools\adb.exe` (always `-s emulator-5556`; never touch `HC720DE260100322`). The provisioning-signed Station 3 APK is installed there: run `adb -s emulator-5556 uninstall com.mitas.ppnam.station3aa` ONCE before the first debug install, then `adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk`. After the uninstall, re-provision in Settings (PIN `079545`): host `10.0.2.2`, port `9001`, Use WebSocket ON, Use TLS OFF, username `test`, password `test`. Logins `operator1`/`pass`, `manager1`/`secret`; badges `BADGE000000000000000001` / `...002`; Settings-shortcut tag `E28011700000021B2F6E9827`.
- Scan broadcasts: RFID `adb -s emulator-5556 shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data <EPC>`; barcode `adb -s emulator-5556 shell am broadcast -a com.scanner.broadcast --es data <CODE>`. Backend mode: `python <SP>\fake_stations\set_mode.py --device scanner_40db7f6eef44 --mode error|timeout|clear` where `<SP>` = `C:\Users\Jonathan\AppData\Local\Temp\claude\C--Dev-Clients-PPNAM\ba7a1680-4205-4b04-bcb6-1b1f23c94914\scratchpad`.
- Keyboard check recipe: tap the field, then `adb -s emulator-5556 shell dumpsys window | findstr ITYPE_IME` gives the IME frame (text keyboard top ≈ 1023 px, numeric PIN pad top ≈ 1155 px); `adb -s emulator-5556 shell uiautomator dump /sdcard/ui.xml && adb -s emulator-5556 pull /sdcard/ui.xml <SP>\ui.xml`, then confirm the primary button's `bounds` bottom < IME top (or that the layout scrolled it into view). Screenshot: `cmd /c "adb -s emulator-5556 exec-out screencap -p > <SP>\shot.png"`.
- OUT OF SCOPE (do not do): change broker/credential defaults (`BrokerSettings` defaults stay `mqtt.sysone.co.za`, 443, WS on, TLS on, blank credentials); any MQTT topic, payload, schema-4.1 or SCRAM change; the station-offline overlay (`layoutStationOffline`, `ScannerApp.checkStationStatus`); session persistence-across-restart semantics (`OperatorSessionHolder` stays in-memory).
- Copy: "Log out" (sentence case) everywhere; danger red `@color/danger` `#E25C5C` for failures; 10 s single-attempt timeouts with the S3 wording "Station 3 did not respond. Check the station and retry."; plurals for counts; no protocol text ("SCRAM…", error codes) ever shown to an operator.

## Review Focus

1. PIN lockout must survive Back + reopen and a process restart (the audit reproduced the bypass on S1 with identical code) — pinned by `PinGateTest` "persisted state keeps the lockout" in Task 6 and the manual Back/reopen check there.
2. A hardware Enter arrives as KEYCODE_ENTER with BOTH an ACTION_DOWN and an ACTION_UP event; the UP must be consumed without a second submit and without moving focus — pinned by `EditorActionsTest` in Task 5.
3. A station rejection with an unknown or protocol-level error code (`timestamp_stale`, `message_id_reused`, a blank code) must never reach the operator as raw text — pinned by `LoginErrorMessagesTest` in Task 9.
4. Auto sign-out "0" means never; a deadline that passes while the app is in the background must fire on the next resume, not an hour later — pinned by `AutoLogoutTest` and `InactivityMonitorTest` in Task 11.
5. Test & Apply against an unreachable broker must end (not spin forever), re-enable the button, keep the saved settings, and show an operator-facing failure — pinned by the manual timeout/rejected checks in Task 12 (no JVM seam exists for `MqttManager`).

---

### Task 1: Branch and baseline

**Files:** none modified.

- [x] **Step 1: Record the working tree**

Run from `C:\Dev\Clients\PPNAM\Station 3\PPNAM_Station_3_AA`:

```
git status --porcelain
git branch --show-current
```

Expected: no output from `--porcelain` (clean) and `master`. If any file is listed, write it down as "pre-existing, untouched" and never stage it in this plan.

- [x] **Step 2: Create the branch**

```
git switch -c fix/ui-audit-2026-10-02
```

- [x] **Step 3: Baseline build and tests**

```
.\gradlew.bat :app:assembleDebug --offline
.\gradlew.bat :app:testDebugUnitTest --offline
```

Expected: `BUILD SUCCESSFUL` for both (if `--offline` fails with "No cached version", rerun without it once, then keep using `--offline`). Record how long assembleDebug took; later tasks use the same command.

- [x] **Step 4: One-time emulator prep**

```
adb -s emulator-5556 uninstall com.mitas.ppnam.station3aa
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 shell settings put system accelerometer_rotation 0
adb -s emulator-5556 shell settings put system user_rotation 0
```

Launch the app, open Settings (wrench), PIN `079545`, enter host `10.0.2.2`, port `9001`, WebSocket ON, TLS OFF, `test`/`test`, Save & Restart. The pill must read Connected. No commit for this task.

---

### Task 2: Portrait lock and adjustResize on Settings (Tier 1 — S3-10 rotation, S3-06 part 1)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:18-37`

- [x] **Step 1: Edit the manifest**

Replace lines 1-41 of `app/src/main/AndroidManifest.xml` so the three `<activity>` entries read:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:name=".ScannerApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.SysOneScanner">

        <!-- Every screen is portrait-locked: the C72 has auto-rotate on and every landscape
             layout was worse in the 2026-10 audit (fields hidden, state lost, dialogs under
             the keyboard). A handheld scanner gains nothing from landscape. -->
        <activity
            android:name=".LoginActivity"
            android:exported="true"
            android:label="Log In"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".MainActivity"
            android:exported="false"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity" />

        <!-- adjustResize (not the adjustPan default): the content is a NestedScrollView, so the
             window must shrink for the keyboard instead of panning the toolbar off-screen. -->
        <activity
            android:name=".SettingsActivity"
            android:exported="false"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.SysOneScanner"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="LockedOrientationActivity" />

    </application>

</manifest>
```

- [x] **Step 2: Compile**

Run: `.\gradlew.bat :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Manual verification on emulator-5556**

```
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 shell settings put system user_rotation 1
```

Open Login, Settings and (after a badge login) Main: each stays portrait. Restore with `user_rotation 0`. Then on Settings tap the PIN field: `adb -s emulator-5556 shell uiautomator dump /sdcard/ui.xml`, pull it, and confirm the `Settings` toolbar title node still has non-zero bounds (not `[0,0][0,0]` as in `station3/05_settings_unlocked.xml`) and the Unlock button's bottom bound is < 1155.

- [x] **Step 4: Commit**

```
git add app/src/main/AndroidManifest.xml
git commit -m "fix(ui): lock every activity to portrait and resize Settings for the keyboard

Closes S3-10 (rotation) and the pan half of S3-06.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 3: Master Batch panel — keyboard no longer covers Select Source (Tier 1 — S3-01, static-01)

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt:10-16, 97-108, 134-152`

Root cause (station3.md S3-01): `enableEdgeToEdge()` with an insets listener that pads `systemBars()` only defeats the manifest's `adjustResize`, and the listener's `setPadding` also wipes the layout's 16dp padding. The audit offers two fixes; this plan drops edge-to-edge on this activity so the theme's bar colours (Task 13) apply identically on all three screens and the window resizes natively.

- [x] **Step 1: Remove edge-to-edge and the insets listener**

In `MainActivity.kt` delete these imports (lines 10, 12, 14, 15):

```kotlin
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
```

and add:

```kotlin
import android.graphics.Rect
```

Replace lines 97-108 (from `binding = ActivityMainBinding.inflate(layoutInflater)` through the insets listener's closing `}`) with:

```kotlin
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        // No enableEdgeToEdge() here: with decorFitsSystemWindows=false the manifest's
        // adjustResize is ignored and the IME inset was never applied, so "Select Source" sat
        // under the keyboard (audit S3-01). Letting the decor fit the system windows means the
        // window shrinks for the keyboard and scrollMasterBatch can scroll the button into view.
```

- [x] **Step 2: Scroll Select Source into view when the keyboard opens**

In `setupMasterBatch()` (currently lines 134-152), after `binding.btnSelectSource.setOnClickListener { ... }` add:

```kotlin
        // The field sits above the keyboard but the button does not; when the window resizes
        // for the IME while the field has focus, bring the button into the visible area.
        binding.scrollMasterBatch.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val heightChanged = (bottom - top) != (oldBottom - oldTop)
            if (heightChanged && binding.etScanValue.hasFocus()) binding.btnSelectSource.post { revealSelectSource() }
        }
        binding.etScanValue.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.btnSelectSource.postDelayed({ revealSelectSource() }, 300)
        }
```

and add this private method after `setupMasterBatch()`:

```kotlin
    /** Asks the enclosing NestedScrollView to scroll until the whole button is on screen. */
    private fun revealSelectSource() {
        val button = binding.btnSelectSource
        if (button.width == 0) return
        button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), false)
    }
```

- [x] **Step 3: Compile**

Run: `.\gradlew.bat :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL` (no unused-import errors; Kotlin warns only).

- [x] **Step 4: Manual verification**

Install, badge-login (`adb -s emulator-5556 shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data BADGE000000000000000001`), tap "Pallet barcode or tag". With the keyboard up (`dumpsys window | findstr ITYPE_IME` → top ≈ 1023), dump the UI and confirm `btnSelectSource` bounds bottom ≤ 1023 (the audit saw `[60,991][1020,1159]`). Also confirm the header card now has a 16dp (48 px) side margin (x starts at 48, not 0) — the old listener had wiped the layout's padding. Type `PALLET-001`, tap Select Source: the green SOURCE SELECTED card appears.

- [x] **Step 5: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt
git commit -m "fix(main): let the window resize for the keyboard and reveal Select Source

Drops enableEdgeToEdge() + the systemBars-only insets listener that defeated adjustResize. Closes S3-01 / static-01 (S3).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 4: Dark-mode theme regression (Tier 1 — static-09)

**Files:**
- Delete: `app/src/main/res/values-night/themes.xml`
- Modify: `app/src/main/res/values/themes.xml:4`

`values-night/themes.xml` re-declares `Base.Theme.SysOneScanner` with no items, so in system dark mode every custom attribute reverts to Material 3 purple. The app is always dark, so the base theme should also stop being `DayNight`.

- [x] **Step 1: Delete the night override and use the Dark parent**

```
git rm app/src/main/res/values-night/themes.xml
```

In `app/src/main/res/values/themes.xml` change line 4 from

```xml
    <style name="Base.Theme.SysOneScanner" parent="Theme.Material3.DayNight.NoActionBar">
```

to

```xml
    <!-- Always dark: a DayNight parent plus the (now deleted) empty values-night override
         reverted every custom colour to M3 purple when the device was in dark mode. -->
    <style name="Base.Theme.SysOneScanner" parent="Theme.Material3.Dark.NoActionBar">
```

- [x] **Step 2: Compile**

Run: `.\gradlew.bat :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Manual verification**

```
adb -s emulator-5556 shell cmd uimode night yes
```

Install, open Settings: the Unlock button is crimson (`#7B1418`), not purple; the PIN field's focus stroke is crimson. Then `adb -s emulator-5556 shell cmd uimode night no` and check the same.

- [x] **Step 4: Commit**

```
git add app/src/main/res/values-night/themes.xml app/src/main/res/values/themes.xml
git commit -m "fix(theme): remove the empty values-night override and use the Dark M3 parent

Closes static-09 for Station 3.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 5: Enter / IME action submits everywhere (Tier 2 — S3-04, S3-05 spell-check part)

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station3aa/EditorActions.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station3aa/EditorActionsTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/SystemBars.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/LoginActivity.kt:10, 79-87, 148-158`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/SettingsActivity.kt:7, 57-65`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt:11, 142-149`
- Modify: `app/src/main/res/layout/activity_login.xml:96-107, 121-128`
- Modify: `app/src/main/res/layout/activity_settings.xml:239-246, 322-327, 340-345, 373-378, 392-397`
- Modify: `app/src/main/res/layout/activity_main.xml:164-172`

**Interfaces:**
- Produces: `EditorActions.decide(actionId: Int, keyCode: Int?, keyAction: Int?): EditorActions.Decision` (SUBMIT / CONSUME / IGNORE) and `fun TextView.onSubmit(action: () -> Unit)`; `fun Activity.hideKeyboard()` in `SystemBars.kt`. Tasks 6, 7, 12 use `onSubmit` and `hideKeyboard`.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/mitas/ppnam/station3aa/EditorActionsTest.kt`:

```kotlin
package com.mitas.ppnam.station3aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.mitas.ppnam.station3aa.EditorActions.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One rule for "the operator pressed submit": an IME Done/Go/Send/Search action, or a hardware
 * Enter (what the C72 keypad and a scanner-wedge suffix send). Enter arrives as KEYCODE_ENTER
 * with a DOWN and then an UP event — submit once on DOWN, swallow the UP so the field neither
 * submits twice nor moves focus to the next view.
 */
class EditorActionsTest {

    @Test
    fun `IME Done, Go, Send and Search submit`() {
        assertEquals(Decision.SUBMIT, EditorActions.decide(EditorInfo.IME_ACTION_DONE, null, null))
        assertEquals(Decision.SUBMIT, EditorActions.decide(EditorInfo.IME_ACTION_GO, null, null))
        assertEquals(Decision.SUBMIT, EditorActions.decide(EditorInfo.IME_ACTION_SEND, null, null))
        assertEquals(Decision.SUBMIT, EditorActions.decide(EditorInfo.IME_ACTION_SEARCH, null, null))
    }

    @Test
    fun `IME Next and Unspecified without a key are ignored`() {
        assertEquals(Decision.IGNORE, EditorActions.decide(EditorInfo.IME_ACTION_NEXT, null, null))
        assertEquals(Decision.IGNORE, EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED, null, null))
    }

    @Test
    fun `hardware Enter submits on key down`() {
        assertEquals(
            Decision.SUBMIT,
            EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            Decision.SUBMIT,
            EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN),
        )
    }

    @Test
    fun `the matching Enter key up is consumed without a second submit`() {
        assertEquals(
            Decision.CONSUME,
            EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP),
        )
    }

    @Test
    fun `other keys are ignored`() {
        assertEquals(
            Decision.IGNORE,
            EditorActions.decide(EditorInfo.IME_ACTION_UNSPECIFIED, KeyEvent.KEYCODE_TAB, KeyEvent.ACTION_DOWN),
        )
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station3aa.EditorActionsTest"`
Expected: compilation FAILS with `Unresolved reference: EditorActions`.

- [x] **Step 3: Implement EditorActions and hideKeyboard**

Create `app/src/main/java/com/mitas/ppnam/station3aa/EditorActions.kt`:

```kotlin
package com.mitas.ppnam.station3aa

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * One rule for "the operator pressed submit" on a text field (audit S3-04).
 *
 * Gboard sends the IME action id (Done/Go). The C72 keypad and a scanner-wedge suffix send a
 * hardware KEYCODE_ENTER, which reaches the editor-action listener with
 * actionId = IME_ACTION_UNSPECIFIED and a KeyEvent — once for ACTION_DOWN and again for
 * ACTION_UP. Submit on DOWN, consume the UP (otherwise the TextView treats it as "move focus to
 * the next view", which is what the audit saw: focus jumped to the button and nothing was sent).
 */
object EditorActions {

    enum class Decision { SUBMIT, CONSUME, IGNORE }

    private val submitActionIds = setOf(
        EditorInfo.IME_ACTION_DONE,
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_SEARCH,
    )

    fun decide(actionId: Int, keyCode: Int?, keyAction: Int?): Decision {
        val isEnterKey = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        return when {
            isEnterKey -> if (keyAction == KeyEvent.ACTION_DOWN) Decision.SUBMIT else Decision.CONSUME
            actionId in submitActionIds -> Decision.SUBMIT
            else -> Decision.IGNORE
        }
    }
}

/** Installs the shared submit rule on a field. [action] runs on IME Done/Go or hardware Enter. */
fun TextView.onSubmit(action: () -> Unit) {
    setOnEditorActionListener { _, actionId, event ->
        when (EditorActions.decide(actionId, event?.keyCode, event?.action)) {
            EditorActions.Decision.SUBMIT -> { action(); true }
            EditorActions.Decision.CONSUME -> true
            EditorActions.Decision.IGNORE -> false
        }
    }
}
```

Replace the whole of `app/src/main/java/com/mitas/ppnam/station3aa/SystemBars.kt` with:

```kotlin
package com.mitas.ppnam.station3aa

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat

/**
 * Forces light (white) status bar icons, matching this app's always-dark background.
 * enableEdgeToEdge()'s own light/dark heuristic doesn't resolve consistently across every
 * screen, leaving status bar icons unreadable on some activities - this makes it explicit.
 */
fun Activity.forceLightStatusBarIcons() {
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
}

/** Hides the soft keyboard so the result of a submit (error line, status row) is visible. */
fun Activity.hideKeyboard() {
    val view = currentFocus ?: window.decorView
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(view.windowToken, 0)
}
```

- [x] **Step 4: Run the test**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station3aa.EditorActionsTest"`
Expected: 5 tests PASS.

- [x] **Step 5: Wire the three screens**

`LoginActivity.kt`: delete the import `import android.view.inputmethod.EditorInfo` (line 10). Replace lines 80-87

```kotlin
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitCredentials()
                true
            } else {
                false
            }
        }
```

with

```kotlin
        binding.etPassword.onSubmit { submitCredentials() }
```

and in `submitCredentials()` (line 148) insert `hideKeyboard()` as the first statement after the `val password = ...` line, so the error line / spinner is visible without the keyboard:

```kotlin
    private fun submitCredentials() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        hideKeyboard()
        if (username.isEmpty() || password.isEmpty()) {
```

`SettingsActivity.kt`: delete `import android.view.inputmethod.EditorInfo` (line 7). Replace lines 58-65 with:

```kotlin
        binding.etPin.onSubmit { submitPin() }
        binding.etBrokerPassword.onSubmit { binding.btnSaveSettings.performClick() }
```

`MainActivity.kt`: delete `import android.view.inputmethod.EditorInfo` (line 11). Replace lines 142-149 (the `binding.etScanValue.setOnEditorActionListener { ... }` block) with:

```kotlin
        binding.etScanValue.onSubmit { submitScan(binding.etScanValue.text?.toString()) }
```

- [x] **Step 6: Make the fields single-line so the IME honours the action**

`activity_login.xml` lines 96-107 (`etUsername`): replace `android:inputType="text"` and `android:maxLines="1"` with

```xml
                            android:inputType="textNoSuggestions"
                            android:singleLine="true"
```

(`textNoSuggestions` also removes the red spell-check underline under "manager1" — S3-05.)

`activity_login.xml` lines 121-128 (`etPassword`): replace `android:maxLines="1"` with `android:singleLine="true"`.

`activity_settings.xml` line 239-246 (`etPin`): add `android:singleLine="true"` after `android:imeOptions="actionDone"`.

`activity_settings.xml` broker fields — add `imeOptions` and `singleLine` so Next chains to the next field and Done on the password triggers the save button:
- `etBrokerHost` (322-327): add `android:imeOptions="actionNext"` and `android:singleLine="true"`.
- `etBrokerPort` (340-345): add `android:imeOptions="actionNext"` and `android:singleLine="true"`.
- `etBrokerUsername` (373-378): add `android:imeOptions="actionNext"` and `android:singleLine="true"`.
- `etBrokerPassword` (392-397): add `android:imeOptions="actionDone"` and `android:singleLine="true"`.

`activity_main.xml` lines 164-172 (`etScanValue`): replace `android:inputType="text"` and `android:maxLines="1"` with

```xml
                            android:inputType="text|textNoSuggestions"
                            android:singleLine="true"
```

- [x] **Step 7: Compile and run all unit tests**

Run: `.\gradlew.bat :app:assembleDebug --offline` then `.\gradlew.bat :app:testDebugUnitTest --offline`
Expected: both `BUILD SUCCESSFUL`.

- [x] **Step 8: Manual verification**

Install. Login: type `manager1`, tap Password, type `wrong`, then `adb -s emulator-5556 shell input keyevent KEYCODE_ENTER`. Expected: the keyboard closes, the spinner shows, then an error line appears (wording is still raw until Task 9) — the request was sent (fake backend log shows `scram_start`). Settings: PIN field, type `1111`, `input keyevent KEYCODE_ENTER` → "Incorrect PIN…" appears without tapping Unlock. Main: focus the scan field, `input text PALLET-001`, `input keyevent KEYCODE_ENTER` → "Selecting source…" then the green card; the Gboard action key now shows the Go arrow, not "→ Next". Settings form: Host → Next lands in Port; Password → Done triggers Save.

- [x] **Step 9: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station3aa/EditorActions.kt app/src/test/java/com/mitas/ppnam/station3aa/EditorActionsTest.kt app/src/main/java/com/mitas/ppnam/station3aa/SystemBars.kt app/src/main/java/com/mitas/ppnam/station3aa/LoginActivity.kt app/src/main/java/com/mitas/ppnam/station3aa/SettingsActivity.kt app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt app/src/main/res/layout/activity_login.xml app/src/main/res/layout/activity_settings.xml app/src/main/res/layout/activity_main.xml
git commit -m "fix(input): hardware Enter and IME Done/Go submit on Login, PIN and scan fields

Shared EditorActions rule (submit on KEYCODE_ENTER down, consume the up), singleLine fields, textNoSuggestions on the username. Closes S3-04 and the spell-check part of S3-05.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 6: Persisted PIN lockout with ticker, blank guard and in-field error (Tier 2 — static-21 / group (c) inferred for S3, S3-06 part 2)

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station3aa/PinGate.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station3aa/PinGateTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/SettingsRepository.kt:22-30, 66-73`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/SettingsActivity.kt:1-31, 46-47, 57, 187-230, 240-243`
- Modify: `app/src/main/res/layout/activity_settings.xml:222-275`
- Modify: `app/src/main/res/values/strings.xml` (Settings block)

**Interfaces:**
- Produces: `PinGate(correctPin, failedAttempts, lockedOutUntilMs)` with `submit(pin, nowMs): Outcome`, `remainingLockoutMs(nowMs)`, `failedAttempts`, `lockedOutUntilMs`; `SettingsRepository.pinGateState(): Pair<Int, Long>` and `savePinGateState(failedAttempts: Int, lockedOutUntilMs: Long)`.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/mitas/ppnam/station3aa/PinGateTest.kt`:

```kotlin
package com.mitas.ppnam.station3aa

import com.mitas.ppnam.station3aa.PinGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supervisor PIN gate: 5 wrong attempts lock the gate for 30 s. The counters are plain fields
 * the activity persists, so Back + reopen or a process restart can no longer reset them
 * (audit group (c): verified bypass on Station 1, identical code here).
 */
class PinGateTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `correct PIN unlocks and resets the counter`() {
        val gate = PinGate("079545", failedAttempts = 3)
        assertEquals(Outcome.Unlocked, gate.submit("079545", t0))
        assertEquals(0, gate.failedAttempts)
        assertEquals(0L, gate.lockedOutUntilMs)
    }

    @Test
    fun `blank PIN is not an attempt`() {
        val gate = PinGate("079545")
        assertEquals(Outcome.Blank, gate.submit("", t0))
        assertEquals(Outcome.Blank, gate.submit("   ", t0))
        assertEquals(0, gate.failedAttempts)
    }

    @Test
    fun `wrong PIN counts down the attempts left`() {
        val gate = PinGate("079545")
        assertEquals(Outcome.Incorrect(attemptsLeft = 4), gate.submit("1111", t0))
        assertEquals(Outcome.Incorrect(attemptsLeft = 3), gate.submit("2222", t0))
        assertEquals(2, gate.failedAttempts)
    }

    @Test
    fun `fifth wrong PIN locks the gate for thirty seconds`() {
        val gate = PinGate("079545", failedAttempts = 4)
        assertEquals(Outcome.LockedOut(remainingMs = 30_000L), gate.submit("0000", t0))
        assertEquals(t0 + 30_000L, gate.lockedOutUntilMs)
        assertEquals(0, gate.failedAttempts)
        assertTrue(gate.isLockedOut(t0 + 29_999L))
        assertFalse(gate.isLockedOut(t0 + 30_000L))
    }

    @Test
    fun `the correct PIN is rejected while locked out`() {
        val gate = PinGate("079545", lockedOutUntilMs = t0 + 10_000L)
        assertEquals(Outcome.LockedOut(remainingMs = 10_000L), gate.submit("079545", t0))
        assertEquals(Outcome.LockedOut(remainingMs = 1L), gate.submit("079545", t0 + 9_999L))
    }

    @Test
    fun `persisted state keeps the lockout across a new instance`() {
        val first = PinGate("079545", failedAttempts = 4)
        first.submit("0000", t0)
        // The activity stores these two values and rebuilds the gate on the next onCreate.
        val reopened = PinGate("079545", first.failedAttempts, first.lockedOutUntilMs)
        assertEquals(Outcome.LockedOut(remainingMs = 25_000L), reopened.submit("079545", t0 + 5_000L))
    }

    @Test
    fun `after the lockout expires the gate works again`() {
        val gate = PinGate("079545", lockedOutUntilMs = t0 + 30_000L)
        assertEquals(0L, gate.remainingLockoutMs(t0 + 30_000L))
        assertEquals(Outcome.Unlocked, gate.submit("079545", t0 + 30_000L))
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station3aa.PinGateTest"`
Expected: FAILS with `Unresolved reference: PinGate`.

- [x] **Step 3: Implement PinGate**

Create `app/src/main/java/com/mitas/ppnam/station3aa/PinGate.kt`:

```kotlin
package com.mitas.ppnam.station3aa

/**
 * Supervisor PIN gate rules, mirroring Station 2's SettingsViewModel: [MAX_ATTEMPTS] wrong PINs
 * lock the gate for [LOCKOUT_MS]. Pure Kotlin so the lockout is unit-testable; the activity
 * persists [failedAttempts] and [lockedOutUntilMs] (wall-clock ms) in SharedPreferences and
 * rebuilds the gate from them, so Back + reopen or a process restart cannot reset the counter.
 */
class PinGate(
    private val correctPin: String,
    failedAttempts: Int = 0,
    lockedOutUntilMs: Long = 0L,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val lockoutMs: Long = LOCKOUT_MS,
) {
    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }

    var failedAttempts: Int = failedAttempts
        private set

    var lockedOutUntilMs: Long = lockedOutUntilMs
        private set

    sealed class Outcome {
        object Unlocked : Outcome()
        /** Empty input is not an attempt (audit: empty Unlock used to burn one). */
        object Blank : Outcome()
        data class Incorrect(val attemptsLeft: Int) : Outcome()
        data class LockedOut(val remainingMs: Long) : Outcome()
    }

    fun remainingLockoutMs(nowMs: Long): Long = (lockedOutUntilMs - nowMs).coerceAtLeast(0L)

    fun isLockedOut(nowMs: Long): Boolean = remainingLockoutMs(nowMs) > 0

    fun submit(pin: String, nowMs: Long): Outcome {
        if (isLockedOut(nowMs)) return Outcome.LockedOut(remainingLockoutMs(nowMs))
        if (pin.isBlank()) return Outcome.Blank
        if (pin == correctPin) {
            failedAttempts = 0
            lockedOutUntilMs = 0L
            return Outcome.Unlocked
        }
        failedAttempts++
        if (failedAttempts >= maxAttempts) {
            failedAttempts = 0
            lockedOutUntilMs = nowMs + lockoutMs
            return Outcome.LockedOut(lockoutMs)
        }
        return Outcome.Incorrect(maxAttempts - failedAttempts)
    }
}
```

- [x] **Step 4: Run the test**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station3aa.PinGateTest"`
Expected: 7 tests PASS.

- [x] **Step 5: Persist the gate state in SettingsRepository**

In `SettingsRepository.kt` add two keys inside `private object Keys` (after line 27):

```kotlin
        const val PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
        const val PIN_LOCKED_UNTIL_MS = "pin_locked_until_ms"
```

and add these methods after `isProvisioned()` (line 67):

```kotlin
    /** Supervisor PIN gate counters, so a lockout survives Back + reopen and a restart. */
    fun pinGateState(): Pair<Int, Long> =
        prefs.getInt(Keys.PIN_FAILED_ATTEMPTS, 0) to prefs.getLong(Keys.PIN_LOCKED_UNTIL_MS, 0L)

    fun savePinGateState(failedAttempts: Int, lockedOutUntilMs: Long) {
        prefs.edit()
            .putInt(Keys.PIN_FAILED_ATTEMPTS, failedAttempts)
            .putLong(Keys.PIN_LOCKED_UNTIL_MS, lockedOutUntilMs)
            .apply()
    }
```

- [x] **Step 6: Move the PIN error into the field and drop the two TextViews**

In `activity_settings.xml` replace lines 222-275 (from the horizontal `<LinearLayout` holding `tilPin` + `btnUnlock` through the closing tag of `tvPinLockout`) with:

```xml
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:gravity="top"
                        android:orientation="horizontal">

                        <!-- The error renders inside the TextInputLayout, directly under the box,
                             so with adjustResize it is brought into view with the field instead of
                             landing under the PIN pad (audit S3-06). -->
                        <com.google.android.material.textfield.TextInputLayout
                            android:id="@+id/tilPin"
                            style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:hint="PIN"
                            app:boxStrokeColor="@color/outline_dark"
                            app:boxStrokeErrorColor="@color/danger"
                            app:errorEnabled="true"
                            app:errorTextColor="@color/danger"
                            app:hintTextColor="@color/text_secondary_dark">

                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etPin"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:inputType="numberPassword"
                                android:maxLength="6"
                                android:imeOptions="actionDone"
                                android:singleLine="true"
                                android:textColor="@color/text_primary_dark" />
                        </com.google.android.material.textfield.TextInputLayout>

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnUnlock"
                            android:layout_width="wrap_content"
                            android:layout_height="56dp"
                            android:layout_marginStart="12dp"
                            android:text="Unlock" />
                    </LinearLayout>
```

- [x] **Step 7: Add the PIN strings**

In `app/src/main/res/values/strings.xml`, after `<string name="label_signed_in_as">…</string>` (line 55) add:

```xml
    <!-- Supervisor PIN gate -->
    <string name="pin_error_blank">Enter the supervisor PIN.</string>
    <plurals name="pin_error_incorrect">
        <item quantity="one">Incorrect PIN. %1$d attempt left before lockout.</item>
        <item quantity="other">Incorrect PIN. %1$d attempts left before lockout.</item>
    </plurals>
    <string name="pin_error_lockout">Too many attempts. Try again in %1$ds.</string>
```

- [x] **Step 8: Rewrite the PIN handling in SettingsActivity**

In `SettingsActivity.kt`:

Add imports `import android.os.Handler` and `import android.os.Looper` (keep the others).

Replace lines 23-31 (the `correctPin`/`failedPinAttempts`/`lockedOutUntilMs` fields and the companion) with:

```kotlin
    // Ported from Station 2's SettingsViewModel so both apps' supervisor lock behave identically.
    private val correctPin = "079545"
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pinGate: PinGate

    private val ticker = Handler(Looper.getMainLooper())
    private val lockoutTick = Runnable { renderLockout() }
    private var lockoutShowing = false
```

Replace lines 46-47

```kotlin
        val settingsRepository = SettingsRepository(this)
        val current = settingsRepository.brokerSettings()
```

with

```kotlin
        settingsRepository = SettingsRepository(this)
        val current = settingsRepository.brokerSettings()
        val (failedAttempts, lockedOutUntilMs) = settingsRepository.pinGateState()
        pinGate = PinGate(correctPin, failedAttempts, lockedOutUntilMs)
        renderLockout()
```

Replace `submitPin()`, `showErrorMessage()`, `showLockoutMessage()` and `hidePinMessages()` (lines 187-230) with:

```kotlin
    private fun submitPin() {
        val outcome = pinGate.submit(binding.etPin.text?.toString().orEmpty(), System.currentTimeMillis())
        settingsRepository.savePinGateState(pinGate.failedAttempts, pinGate.lockedOutUntilMs)
        when (outcome) {
            PinGate.Outcome.Unlocked -> {
                binding.tilPin.error = null
                binding.etPin.setText("")
                hideKeyboard()
                binding.cardPinLock.visibility = View.GONE
                binding.groupSettingsFields.visibility = View.VISIBLE
            }
            PinGate.Outcome.Blank -> binding.tilPin.error = getString(R.string.pin_error_blank)
            is PinGate.Outcome.Incorrect -> {
                binding.etPin.setText("")
                binding.tilPin.error = resources.getQuantityString(
                    R.plurals.pin_error_incorrect, outcome.attemptsLeft, outcome.attemptsLeft,
                )
            }
            is PinGate.Outcome.LockedOut -> {
                binding.etPin.setText("")
                renderLockout()
            }
        }
    }

    /**
     * Shows the live countdown while locked out, disabling the field and Unlock, and re-enables
     * them the second the lockout ends. Reschedules itself every second while locked.
     */
    private fun renderLockout() {
        ticker.removeCallbacks(lockoutTick)
        val remainingMs = pinGate.remainingLockoutMs(System.currentTimeMillis())
        if (remainingMs > 0) {
            lockoutShowing = true
            binding.etPin.isEnabled = false
            binding.btnUnlock.isEnabled = false
            binding.tilPin.error = getString(R.string.pin_error_lockout, (remainingMs + 999) / 1_000)
            ticker.postDelayed(lockoutTick, 1_000)
        } else if (lockoutShowing) {
            lockoutShowing = false
            binding.etPin.isEnabled = true
            binding.btnUnlock.isEnabled = true
            binding.tilPin.error = null
        }
    }
```

In `onDestroy()` (line 240-243) add `ticker.removeCallbacks(lockoutTick)` before the `removeConnectionStatusListener` line.

- [x] **Step 9: Compile and run all tests**

Run: `.\gradlew.bat :app:assembleDebug --offline` then `.\gradlew.bat :app:testDebugUnitTest --offline`
Expected: both `BUILD SUCCESSFUL`. (ViewBinding no longer generates `tvPinError`/`tvPinLockout`; the compile proves nothing else referenced them.)

- [x] **Step 10: Manual verification**

Install, open Settings. (1) Tap Unlock with an empty PIN → "Enter the supervisor PIN." under the field; attempts are unchanged (next wrong PIN says "4 attempts left"). (2) Enter five wrong PINs → "Too many attempts. Try again in 30s." counting down every second, Unlock and the field greyed. (3) Press Back, reopen Settings (wrench) → still locked with the countdown continuing. (4) `adb -s emulator-5556 shell am force-stop com.mitas.ppnam.station3aa`, relaunch, open Settings → still locked if < 30 s have passed. (5) After it reaches 0 the field re-enables; `079545` unlocks. (6) Keyboard check: with a wrong PIN and the numeric pad up, the error text node's bounds bottom is < 1155.

- [x] **Step 11: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station3aa/PinGate.kt app/src/test/java/com/mitas/ppnam/station3aa/PinGateTest.kt app/src/main/java/com/mitas/ppnam/station3aa/SettingsRepository.kt app/src/main/java/com/mitas/ppnam/station3aa/SettingsActivity.kt app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml
git commit -m "fix(settings): persist the PIN lockout, tick the countdown, ignore blank Unlock

PinGate (tested) + SharedPreferences state; error shown inside the PIN field. Closes the inferred group (c) findings for Station 3 and the error-placement half of S3-06.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 7: Login layout standard — error above the fields, password toggle, button scrolled into view (Tier 2 — S3-03, §5 "Login layout")

**Files:**
- Modify: `app/src/main/res/layout/activity_login.xml:49-58, 110-141`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/LoginActivity.kt:3-15, 88-98, 190-193`

- [x] **Step 1: Move the error line above the fields and add the visibility toggle**

In `activity_login.xml`:

Give the scroller an id — change the opening tag at line 49-58 to:

```xml
    <androidx.core.widget.NestedScrollView
        android:id="@+id/scrollLogin"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:clipToPadding="false"
        android:fillViewport="true"
        android:overScrollMode="never"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/appBarLayout">
```

Delete the `tvLoginError` TextView at lines 131-140 and insert it as the FIRST child of the card's inner `LinearLayout` (immediately after the `android:padding="24dp">` line at 81), with a bottom margin instead of a top margin:

```xml
                    <!-- Above the fields (audit S3-03): an error that grows the form from below
                         pushed Log In under the keyboard. Up here it never moves the button. -->
                    <TextView
                        android:id="@+id/tvLoginError"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginBottom="16dp"
                        android:textColor="@color/danger"
                        android:textSize="15sp"
                        android:visibility="gone"
                        tools:text="Login failed"
                        tools:visibility="visible" />
```

In the password `TextInputLayout` (lines 110-119) replace `app:passwordToggleEnabled="false"` with:

```xml
                        app:endIconMode="password_toggle"
                        app:endIconTint="@color/text_secondary_dark"
```

- [x] **Step 2: Scroll Log In into view on password focus and when an error shows**

In `LoginActivity.kt` add `import android.graphics.Rect`. After the line `binding.btnLogin.applyPressScaleFeedback()` (line 93) add:

```kotlin
        // The error line lives above the fields; the button is the thing that can end up below
        // the keyboard, so pull it into view whenever the password field takes focus.
        binding.etPassword.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.btnLogin.postDelayed({ revealLoginButton() }, 300)
        }
```

Replace `showError()` (lines 190-193) with:

```kotlin
    private fun showError(message: String) {
        binding.tvLoginError.text = message
        binding.tvLoginError.visibility = View.VISIBLE
        binding.scrollLogin.post { binding.scrollLogin.smoothScrollTo(0, 0) }
    }

    /** Asks the NestedScrollView to scroll until the whole Log In button is visible. */
    private fun revealLoginButton() {
        val button = binding.btnLogin
        if (button.width == 0) return
        button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), false)
    }
```

- [x] **Step 3: Compile**

Run: `.\gradlew.bat :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Manual verification**

Install. Login with `manager1` / `wrong` (tap Log In). The red error appears ABOVE the Username field (dump: `tvLoginError` bounds top < `tilUsername` bounds top). Tap Password again: with the keyboard up (`ITYPE_IME` top ≈ 1023) `btnLogin`'s bottom bound is ≤ 1023 (audit saw `[160,982][920,1023]`, a 41 px sliver). The eye icon at the end of the password field toggles masking. Tap Log In with both fields empty → "Please fill in all fields" above the fields.

- [x] **Step 5: Commit**

```
git add app/src/main/res/layout/activity_login.xml app/src/main/java/com/mitas/ppnam/station3aa/LoginActivity.kt
git commit -m "fix(login): error line above the fields, password visibility toggle, button kept above the keyboard

Closes S3-03 and the login-layout rows of the consistency matrix.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 8: "Close the app?" on the main screen (Tier 2 — S3-02 back half, static-04)

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt:12-13, 110-117, 257-266`

- [x] **Step 1: Add the back handler and dialog**

In `MainActivity.kt` add `import androidx.activity.addCallback` and `import androidx.appcompat.app.AlertDialog`. After `OperatorSessionHolder.addListener(sessionListener)` (line 116) add:

```kotlin
        // Back on the home screen used to drop straight to the Android launcher with the operator
        // still signed in (audit S3-02). Ask first, exactly like Login and Station 2's Home.
        onBackPressedDispatcher.addCallback(this) { showExitDialog() }
```

After `showLogoutDialog()` (line 266) add:

```kotlin
    private fun showExitDialog() {
        AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle(getString(R.string.exit_dialog_title))
            .setMessage(getString(R.string.exit_dialog_message))
            .setPositiveButton(getString(R.string.exit_dialog_close)) { _, _ -> finishAffinity() }
            .setNegativeButton(getString(R.string.exit_dialog_stay), null)
            .show()
    }
```

and change `showLogoutDialog()`'s first line from `androidx.appcompat.app.AlertDialog.Builder(this, R.style.AppAlertDialogTheme)` to `AlertDialog.Builder(this, R.style.AppAlertDialogTheme)` (same class, now imported). Task 13 swaps both to `MaterialAlertDialogBuilder`.

- [x] **Step 2: Compile**

Run: `.\gradlew.bat :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Manual verification**

Install, badge-login, press Back: "Close the app?" / "You'll leave PPNAM Station 3 and return to the home screen." with [Stay] [Close]. Stay → still on Main. Back again, Close → launcher. Relaunch → Main (session kept; that is the agreed persistence semantics — the inactivity timer in Task 11 is what ends an abandoned session).

- [x] **Step 4: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt
git commit -m "fix(main): confirm before Back closes the app

Closes S3-02 (exit confirmation) / static-04 for Station 3.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 9: Operator-facing login errors (Tier 2 — S3-05, group (f), static-08 login wording)

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station3aa/LoginErrorMessages.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station3aa/LoginErrorMessagesTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/AuthClient.kt:38-43, 57, 105, 130, 141, 191-253`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/LoginActivity.kt:168-178`
- Modify: `app/src/main/res/values/strings.xml` (Login block)

**Interfaces:**
- Produces: `enum class AuthStage { START, PROOF, BADGE, OPERATOR_LIST, LOCAL }`, `class AuthFailure(val stage: AuthStage, val code: String, message: String) : Exception(message)` (both in `AuthClient.kt`); `enum class LoginErrorKind`; `LoginErrorMessages.kindFor(stage, code)` and `kindFor(error: Throwable)`; string codes `LoginErrorMessages.CODE_TIMEOUT`, `CODE_NOT_CONNECTED`, `CODE_PUBLISH_FAILED`.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/mitas/ppnam/station3aa/LoginErrorMessagesTest.kt`:

```kotlin
package com.mitas.ppnam.station3aa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The station's `reason` text ("SCRAM proof rejected.") and error codes are protocol detail;
 * operators get one of five fixed wordings chosen from WHERE the login failed and the code.
 */
class LoginErrorMessagesTest {

    @Test
    fun `a rejected proof is incorrect credentials`() {
        assertEquals(LoginErrorKind.INVALID_CREDENTIALS, LoginErrorMessages.kindFor(AuthStage.PROOF, "scram_proof_invalid"))
    }

    @Test
    fun `a rejected start (unknown username) is incorrect credentials`() {
        assertEquals(LoginErrorKind.INVALID_CREDENTIALS, LoginErrorMessages.kindFor(AuthStage.START, "unknown_user"))
        assertEquals(LoginErrorKind.INVALID_CREDENTIALS, LoginErrorMessages.kindFor(AuthStage.START, ""))
    }

    @Test
    fun `a rejected badge is badge unknown`() {
        assertEquals(LoginErrorKind.BADGE_UNKNOWN, LoginErrorMessages.kindFor(AuthStage.BADGE, "badge_unknown"))
        assertEquals(LoginErrorKind.BADGE_UNKNOWN, LoginErrorMessages.kindFor(AuthStage.BADGE, ""))
    }

    @Test
    fun `timeouts and broker problems are reported as such at every stage`() {
        for (stage in AuthStage.values()) {
            assertEquals(LoginErrorKind.TIMEOUT, LoginErrorMessages.kindFor(stage, LoginErrorMessages.CODE_TIMEOUT))
            assertEquals(LoginErrorKind.NOT_CONNECTED, LoginErrorMessages.kindFor(stage, LoginErrorMessages.CODE_NOT_CONNECTED))
            assertEquals(LoginErrorKind.NOT_CONNECTED, LoginErrorMessages.kindFor(stage, LoginErrorMessages.CODE_PUBLISH_FAILED))
        }
    }

    @Test
    fun `protocol-level codes are never blamed on the operator`() {
        assertEquals(LoginErrorKind.STATION_ERROR, LoginErrorMessages.kindFor(AuthStage.PROOF, "timestamp_stale"))
        assertEquals(LoginErrorKind.STATION_ERROR, LoginErrorMessages.kindFor(AuthStage.START, "message_id_reused"))
        assertEquals(LoginErrorKind.STATION_ERROR, LoginErrorMessages.kindFor(AuthStage.BADGE, "invalid_envelope"))
    }

    @Test
    fun `local checks and anything not an AuthFailure are a station error`() {
        assertEquals(LoginErrorKind.STATION_ERROR, LoginErrorMessages.kindFor(AuthStage.LOCAL, ""))
        assertEquals(LoginErrorKind.STATION_ERROR, LoginErrorMessages.kindFor(IllegalStateException("boom")))
        assertEquals(
            LoginErrorKind.INVALID_CREDENTIALS,
            LoginErrorMessages.kindFor(AuthFailure(AuthStage.PROOF, "scram_proof_invalid", "SCRAM proof rejected.")),
        )
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station3aa.LoginErrorMessagesTest"`
Expected: FAILS with `Unresolved reference: LoginErrorMessages` (and `AuthStage`).

- [x] **Step 3: Give AuthClient failures a stage and a code**

In `AuthClient.kt`:

Add at the bottom of the file (after the `toStringList()` extension):

```kotlin
/** Where in the login exchange a failure happened; the UI maps (stage, code) to wording. */
enum class AuthStage { START, PROOF, BADGE, OPERATOR_LIST, LOCAL }

/**
 * A login failure with the station's machine-readable [code] (or one of LoginErrorMessages'
 * local codes) and [stage]. [message] is the station's sanitized reason — logged, never shown.
 */
class AuthFailure(val stage: AuthStage, val code: String, message: String) : Exception(message)
```

Replace the private `failure()` helper (line 253) with:

```kotlin
    private fun failure(message: String, stage: AuthStage = AuthStage.LOCAL, code: String = ""): Result<Nothing> =
        Result.failure(AuthFailure(stage, code, message))
```

Change the `request()` signature (lines 191-196) to take the stage:

```kotlin
    private fun request(
        requestType: String,
        responseType: String,
        payload: JSONObject,
        stage: AuthStage,
        onResult: (Result<JSONObject>) -> Unit,
    ) {
```

and inside it:
- line 198: `mainHandler.post { onResult(failure("Not connected to the station", stage, LoginErrorMessages.CODE_NOT_CONNECTED)) }`
- line 220: `timeoutRunnable = Runnable { finish(failure("Station did not respond", stage, LoginErrorMessages.CODE_TIMEOUT)) }`
- line 235: `else finish(failure(Schema41.rejectionMessage(json), stage, json.optString("errorCode", "")))`
- line 241: `finish(failure(Schema41.rejectionMessage(json), stage, json.optString("errorCode", "")))`
- line 250: `if (throwable != null) finish(failure("Could not reach the station", stage, LoginErrorMessages.CODE_PUBLISH_FAILED))`

Update the four call sites:
- line 57: `request("scram_start_requested", "scram_challenge", startPayload, AuthStage.START) { startResult ->`
- line 105: `request("scram_proof_requested", "scram_proof_result", proofPayload, AuthStage.PROOF) { proofResult ->`
- line 130: `request("operator_list_requested", "operator_list", payload, AuthStage.OPERATOR_LIST) { result ->`
- line 141: `request("login_requested", "operator_context", payload, AuthStage.BADGE) { result ->`

- [x] **Step 4: Implement LoginErrorMessages**

Create `app/src/main/java/com/mitas/ppnam/station3aa/LoginErrorMessages.kt`:

```kotlin
package com.mitas.ppnam.station3aa

/** The five things an operator can be told about a failed login. */
enum class LoginErrorKind { INVALID_CREDENTIALS, BADGE_UNKNOWN, TIMEOUT, NOT_CONNECTED, STATION_ERROR }

/**
 * Maps a login failure to operator wording (audit S3-05 / group (f)): the station's free-text
 * reason ("SCRAM proof rejected.") and its error codes are protocol detail and never shown.
 * Pure Kotlin so the mapping is unit-tested; LoginActivity turns the kind into a string resource.
 */
object LoginErrorMessages {

    /** Local (non-station) codes AuthClient attaches to its own failures. */
    const val CODE_TIMEOUT = "timeout"
    const val CODE_NOT_CONNECTED = "not_connected"
    const val CODE_PUBLISH_FAILED = "publish_failed"

    /** Envelope/replay rejections: a device or clock problem, not a wrong password. */
    private val protocolCodes = setOf(
        "invalid_envelope", "message_id_reused", "timestamp_stale", "timestamp_invalid",
        "schema_version_unsupported", "unknown_request_type", "device_not_registered",
    )

    fun kindFor(stage: AuthStage, code: String): LoginErrorKind {
        val normalized = code.trim().lowercase()
        return when {
            normalized == CODE_TIMEOUT -> LoginErrorKind.TIMEOUT
            normalized == CODE_NOT_CONNECTED || normalized == CODE_PUBLISH_FAILED -> LoginErrorKind.NOT_CONNECTED
            normalized in protocolCodes || normalized.startsWith("timestamp") -> LoginErrorKind.STATION_ERROR
            stage == AuthStage.START || stage == AuthStage.PROOF -> LoginErrorKind.INVALID_CREDENTIALS
            stage == AuthStage.BADGE -> LoginErrorKind.BADGE_UNKNOWN
            else -> LoginErrorKind.STATION_ERROR
        }
    }

    fun kindFor(error: Throwable): LoginErrorKind =
        (error as? AuthFailure)?.let { kindFor(it.stage, it.code) } ?: LoginErrorKind.STATION_ERROR
}
```

- [x] **Step 5: Run the test**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station3aa.LoginErrorMessagesTest"`
Expected: 6 tests PASS.

- [x] **Step 6: Add the strings and use them in LoginActivity**

In `strings.xml`, after `<string name="label_or_scan_badge">…</string>` (line 30) add:

```xml
    <!-- Login failures, chosen by LoginErrorMessages — never the station's raw reason text -->
    <string name="login_error_invalid_credentials">Incorrect username or password.</string>
    <string name="login_error_badge_unknown">Badge not recognised. Try again or log in with your username.</string>
    <string name="login_error_timeout">Station 3 did not respond. Check the station and retry.</string>
    <string name="login_error_not_connected">Not connected to the broker. Check Settings and retry.</string>
    <string name="login_error_station">Station 3 could not complete the login. Try again.</string>
```

In `LoginActivity.kt` replace `onLoginResult()` (lines 168-178) with:

```kotlin
    private fun onLoginResult(result: Result<OperatorSession>) {
        result
            .onSuccess {
                loggedIn = true
                goHome()
            }
            .onFailure { e ->
                setLoggingIn(false)
                android.util.Log.w("LoginActivity", "Login failed: ${e.message}")
                showError(getString(loginErrorText(LoginErrorMessages.kindFor(e))))
            }
    }

    private fun loginErrorText(kind: LoginErrorKind): Int = when (kind) {
        LoginErrorKind.INVALID_CREDENTIALS -> R.string.login_error_invalid_credentials
        LoginErrorKind.BADGE_UNKNOWN -> R.string.login_error_badge_unknown
        LoginErrorKind.TIMEOUT -> R.string.login_error_timeout
        LoginErrorKind.NOT_CONNECTED -> R.string.login_error_not_connected
        LoginErrorKind.STATION_ERROR -> R.string.login_error_station
    }
```

- [x] **Step 7: Compile and run all tests**

Run: `.\gradlew.bat :app:assembleDebug --offline` then `.\gradlew.bat :app:testDebugUnitTest --offline`
Expected: both `BUILD SUCCESSFUL`.

- [x] **Step 8: Manual verification**

Install. `manager1`/`wrong` → "Incorrect username or password." (not "SCRAM proof rejected."). Badge `BADGE000000000000000009` → "Badge not recognised…". `python <SP>\fake_stations\set_mode.py --device scanner_40db7f6eef44 --mode timeout`, then `manager1`/`secret` → after 10 s "Station 3 did not respond. Check the station and retry."; `--mode clear` afterwards. Disconnect the broker (set port 9002 in Settings, Save) → login shows "Not connected to the broker…"; restore port 9001.

- [x] **Step 9: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station3aa/LoginErrorMessages.kt app/src/test/java/com/mitas/ppnam/station3aa/LoginErrorMessagesTest.kt app/src/main/java/com/mitas/ppnam/station3aa/AuthClient.kt app/src/main/java/com/mitas/ppnam/station3aa/LoginActivity.kt app/src/main/res/values/strings.xml
git commit -m "fix(login): map station rejections to operator wording instead of protocol text

AuthFailure carries stage + errorCode; LoginErrorMessages (tested) picks one of five strings. Closes S3-05 and the Station 3 part of group (f) / static-08 login wording.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 10: Keep the scan status and selected source across activity recreation (Tier 2 — S3-10 status part)

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt:34-38, 110-113, 207-227`
- Modify: `app/src/main/res/layout/activity_main.xml:254-299`

Portrait lock (Task 2) removes rotation, but a font-size/locale change or process recreation still recreates the activity; belt-and-braces per §7 item 16.

- [x] **Step 1: Freeze the source card texts**

In `activity_main.xml` add `android:freezesText="true"` to each of the five TextViews `tvSourceProduct` (254-262), `tvSourceDescription` (264-270), `tvSourceWeight` (272-280), `tvSourceDetails` (282-290) and `tvSourceInstruction` (292-299), e.g.:

```xml
                    <TextView
                        android:id="@+id/tvSourceProduct"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="6dp"
                        android:freezesText="true"
                        android:textColor="@color/text_primary"
                        android:textSize="28sp"
                        android:textStyle="bold"
                        tools:text="1500000001" />
```

- [x] **Step 2: Save and restore the status row in MainActivity**

Add fields after `private val kgFormat = DecimalFormat("#,##0.###")` (line 38):

```kotlin
    private var statusIsError = false

    private companion object {
        const val STATE_LAST_SCAN = "last_scan_value"
        const val STATE_STATUS_VISIBLE = "status_visible"
        const val STATE_STATUS_TEXT = "status_text"
        const val STATE_STATUS_RETRY = "status_retry"
        const val STATE_STATUS_ERROR = "status_error"
        const val STATE_SOURCE_VISIBLE = "source_visible"
        const val STATE_SOURCE_DESCRIPTION_VISIBLE = "source_description_visible"
        const val STATE_SOURCE_INSTRUCTION_VISIBLE = "source_instruction_visible"
    }
```

In `onCreate()` right after `setupMasterBatch()` (line 112) add:

```kotlin
        savedInstanceState?.let { restoreScanState(it) }
```

In `showScanStatus()` (line 221-227) add `statusIsError = error` as the first statement.

Add these methods after `showScanStatus()`:

```kotlin
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::binding.isInitialized) return
        outState.putString(STATE_LAST_SCAN, lastScanValue)
        outState.putBoolean(STATE_STATUS_VISIBLE, binding.layoutScanStatus.visibility == View.VISIBLE)
        outState.putString(STATE_STATUS_TEXT, binding.tvScanStatus.text?.toString())
        outState.putBoolean(STATE_STATUS_RETRY, binding.btnRetryScan.visibility == View.VISIBLE)
        outState.putBoolean(STATE_STATUS_ERROR, statusIsError)
        outState.putBoolean(STATE_SOURCE_VISIBLE, binding.cardSource.visibility == View.VISIBLE)
        outState.putBoolean(STATE_SOURCE_DESCRIPTION_VISIBLE, binding.tvSourceDescription.visibility == View.VISIBLE)
        outState.putBoolean(STATE_SOURCE_INSTRUCTION_VISIBLE, binding.tvSourceInstruction.visibility == View.VISIBLE)
    }

    /** Status text and the selected-source card are not View state; restore them by hand. */
    private fun restoreScanState(state: Bundle) {
        lastScanValue = state.getString(STATE_LAST_SCAN)
        if (state.getBoolean(STATE_SOURCE_VISIBLE)) {
            binding.cardSource.visibility = View.VISIBLE
            binding.tvSourceDescription.visibility =
                if (state.getBoolean(STATE_SOURCE_DESCRIPTION_VISIBLE)) View.VISIBLE else View.GONE
            binding.tvSourceInstruction.visibility =
                if (state.getBoolean(STATE_SOURCE_INSTRUCTION_VISIBLE)) View.VISIBLE else View.GONE
        }
        val text = state.getString(STATE_STATUS_TEXT).orEmpty()
        if (state.getBoolean(STATE_STATUS_VISIBLE) && text.isNotBlank()) {
            showScanStatus(
                text,
                pending = workflow.isScanPending,
                retry = state.getBoolean(STATE_STATUS_RETRY),
                error = state.getBoolean(STATE_STATUS_ERROR),
            )
        }
    }
```

- [x] **Step 3: Compile**

Run: `.\gradlew.bat :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Manual verification**

Install, badge-login, set `--mode timeout`, scan `PALLET-001`, wait for "Station 3 did not respond…" + Retry. Force a recreation without rotation: `adb -s emulator-5556 shell settings put system font_scale 1.15` then back to `1.0`. The status text and Retry survive. `--mode clear`, Retry → green card; repeat the font-scale toggle → the card and its texts survive.

- [x] **Step 5: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt app/src/main/res/layout/activity_main.xml
git commit -m "fix(main): keep scan status and selected source across activity recreation

Closes the status-loss part of S3-10.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 11: Inactivity auto sign-out with a reason on Login (Tier 3 — static-05 / group (j), S3-02 session half, station1-13-style plurals)

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station3aa/AutoLogout.kt` (copied from S1)
- Create: `app/src/main/java/com/mitas/ppnam/station3aa/InactivityMonitor.kt` (copied from S1)
- Create: `app/src/main/java/com/mitas/ppnam/station3aa/SessionActivity.kt` (copied from S1)
- Create: `app/src/main/java/com/mitas/ppnam/station3aa/SessionGuard.kt` (adapted from S1)
- Create: `app/src/test/java/com/mitas/ppnam/station3aa/AutoLogoutTest.kt`
- Create: `app/src/test/java/com/mitas/ppnam/station3aa/InactivityMonitorTest.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/SettingsRepository.kt:22-30, 66-73`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/AuthClient.kt:145-155`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/ScannerApp.kt:62-67`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt:13, 29, 73-86`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/SettingsActivity.kt:9, 12, 40, 160-185, onDestroy`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `OperatorSessionHolder.clear(reason)` (existing), `LoginActivity` already shows `OperatorSessionHolder.signedOutReason` once (lines 74-77).
- Produces: `AutoLogout.DEFAULT_MINUTES = 15`, `AutoLogout.MAX_MINUTES = 1440`, `AutoLogout.parseMinutes(text): Int?`, `AutoLogout.timeoutMs(minutes): Long`; `SettingsRepository.autoLogoutMinutes(): Int` / `saveAutoLogoutMinutes(minutes)`; `SessionGuard.install(app)`, `touch()`, `checkNow()`, `applyTimeout()`, `signOut(reason)`; `AuthClient.logout(reason: String? = null, onComplete: () -> Unit = {})`; abstract `SessionActivity`. Task 12 calls `AutoLogout.parseMinutes`, `saveAutoLogoutMinutes`, `SessionGuard.applyTimeout()`.

- [x] **Step 1: Write the failing tests**

Create `app/src/test/java/com/mitas/ppnam/station3aa/AutoLogoutTest.kt`:

```kotlin
package com.mitas.ppnam.station3aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inactivity auto sign-out setting rules, copied from Station 1 (whole minutes, 0 = never). */
class AutoLogoutTest {

    @Test
    fun `default is fifteen minutes`() {
        assertEquals(15, AutoLogout.DEFAULT_MINUTES)
    }

    @Test
    fun `parses whole minutes within range`() {
        assertEquals(0, AutoLogout.parseMinutes("0"))
        assertEquals(15, AutoLogout.parseMinutes(" 15 "))
        assertEquals(1440, AutoLogout.parseMinutes("1440"))
    }

    @Test
    fun `rejects blanks, negatives, decimals and out-of-range values`() {
        assertNull(AutoLogout.parseMinutes(""))
        assertNull(AutoLogout.parseMinutes("-1"))
        assertNull(AutoLogout.parseMinutes("1.5"))
        assertNull(AutoLogout.parseMinutes("1441"))
        assertNull(AutoLogout.parseMinutes("abc"))
    }

    @Test
    fun `timeout in milliseconds, zero means disabled`() {
        assertEquals(0L, AutoLogout.timeoutMs(0))
        assertEquals(0L, AutoLogout.timeoutMs(-3))
        assertEquals(60_000L, AutoLogout.timeoutMs(1))
        assertEquals(900_000L, AutoLogout.timeoutMs(15))
    }
}
```

Create `app/src/test/java/com/mitas/ppnam/station3aa/InactivityMonitorTest.kt`:

```kotlin
package com.mitas.ppnam.station3aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inactivity auto sign-out timer (copied from Station 1). Time and scheduling are injected so
 * the tests are deterministic: `scheduled` holds the pending runnable (at most one) and
 * `fireScheduled()` advances the clock to its due time and runs it.
 */
class InactivityMonitorTest {

    private var now = 1_000_000L
    private var scheduled: Pair<Long, Runnable>? = null
    private var expired = 0

    private val monitor = InactivityMonitor(
        now = { now },
        schedule = { delay, r -> scheduled = (now + delay) to r },
        cancel = { r -> if (scheduled?.second === r) scheduled = null },
        onExpired = { expired++ },
    )

    private fun fireScheduled() {
        val (due, r) = scheduled ?: error("nothing scheduled")
        scheduled = null
        now = maxOf(now, due)
        r.run()
    }

    @Test
    fun `expires once the timeout elapses without activity`() {
        monitor.start(60_000)
        assertTrue(monitor.isRunning)
        fireScheduled()
        assertEquals(1, expired)
        assertFalse(monitor.isRunning)
    }

    @Test
    fun `touch defers the deadline`() {
        monitor.start(60_000)
        now += 40_000
        monitor.touch()
        // The original deadline arrives: only 20s since the touch, so no expiry yet.
        fireScheduled()
        assertEquals(0, expired)
        assertTrue(monitor.isRunning)
        assertEquals(now + 40_000, scheduled!!.first)
        fireScheduled()
        assertEquals(1, expired)
    }

    @Test
    fun `stop cancels the pending deadline and never fires`() {
        monitor.start(60_000)
        monitor.stop()
        assertFalse(monitor.isRunning)
        assertNull(scheduled)
        assertEquals(0, expired)
    }

    @Test
    fun `checkNow after a long gap fires immediately`() {
        monitor.start(60_000)
        now += 3_600_000 // app was in the background for an hour
        monitor.checkNow()
        assertEquals(1, expired)
        assertNull(scheduled)
    }

    @Test
    fun `checkNow before the deadline does nothing`() {
        monitor.start(60_000)
        now += 10_000
        monitor.checkNow()
        assertEquals(0, expired)
        assertTrue(monitor.isRunning)
    }

    @Test
    fun `zero timeout never starts`() {
        monitor.start(0)
        assertFalse(monitor.isRunning)
        assertNull(scheduled)
        monitor.touch()
        monitor.checkNow()
        assertEquals(0, expired)
    }

    @Test
    fun `restart replaces the previous deadline`() {
        monitor.start(60_000)
        val first = scheduled!!.second
        monitor.start(120_000)
        assertTrue(scheduled!!.second !== first)
        assertEquals(now + 120_000, scheduled!!.first)
    }
}
```

- [x] **Step 2: Run them to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station3aa.AutoLogoutTest" --tests "com.mitas.ppnam.station3aa.InactivityMonitorTest"`
Expected: FAILS with `Unresolved reference: AutoLogout` / `InactivityMonitor`.

- [x] **Step 3: Copy the pure classes from Station 1**

Create `app/src/main/java/com/mitas/ppnam/station3aa/AutoLogout.kt`:

```kotlin
package com.mitas.ppnam.station3aa

/** Inactivity auto sign-out setting rules (Station 1 spec §3): whole minutes, 0 = never, max one day. */
object AutoLogout {
    const val DEFAULT_MINUTES = 15
    const val MAX_MINUTES = 1440

    fun parseMinutes(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 0..MAX_MINUTES }

    fun timeoutMs(minutes: Int): Long = if (minutes <= 0) 0L else minutes * 60_000L
}
```

Create `app/src/main/java/com/mitas/ppnam/station3aa/InactivityMonitor.kt`:

```kotlin
package com.mitas.ppnam.station3aa

/**
 * Inactivity auto sign-out timer (copied from Station 1). Pure Kotlin: the caller supplies a
 * monotonic clock and a scheduler, so production uses SystemClock.elapsedRealtime plus a
 * main-thread Handler while tests drive time by hand.
 *
 * The deadline is wall-clock from the last activity, so time spent in the background still
 * counts; hosts call [checkNow] on resume to catch a deadline that passed while no Handler was
 * running. [onExpired] fires at most once per [start].
 */
class InactivityMonitor(
    private val now: () -> Long,
    private val schedule: (Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onExpired: () -> Unit,
) {
    private var timeoutMs = 0L
    private var lastActivity = 0L
    private var pending: Runnable? = null

    val isRunning: Boolean get() = timeoutMs > 0

    fun start(timeoutMs: Long) {
        stop()
        if (timeoutMs <= 0) return
        this.timeoutMs = timeoutMs
        lastActivity = now()
        scheduleCheck(timeoutMs)
    }

    fun touch() {
        if (!isRunning) return
        lastActivity = now()
    }

    fun checkNow() {
        if (!isRunning) return
        val remaining = timeoutMs - (now() - lastActivity)
        if (remaining <= 0) {
            stop()
            onExpired()
        } else {
            scheduleCheck(remaining)
        }
    }

    fun stop() {
        timeoutMs = 0
        pending?.let(cancel)
        pending = null
    }

    private fun scheduleCheck(delayMs: Long) {
        pending?.let(cancel)
        val r = Runnable {
            pending = null
            checkNow()
        }
        pending = r
        schedule(delayMs, r)
    }
}
```

Create `app/src/main/java/com/mitas/ppnam/station3aa/SessionActivity.kt`:

```kotlin
package com.mitas.ppnam.station3aa

import androidx.appcompat.app.AppCompatActivity

/**
 * Base for every screen that can host a signed-in operator: each touch or key press counts as
 * activity for the inactivity auto sign-out. Scanner broadcasts don't pass through
 * onUserInteraction, so receivers call SessionGuard.touch() themselves.
 */
abstract class SessionActivity : AppCompatActivity() {
    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionGuard.touch()
    }
}
```

- [x] **Step 4: Run the tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station3aa.AutoLogoutTest" --tests "com.mitas.ppnam.station3aa.InactivityMonitorTest"`
Expected: 11 tests PASS.

- [x] **Step 5: Persist the minutes and let logout carry a reason**

`SettingsRepository.kt`: add to `Keys` (after the PIN keys from Task 6):

```kotlin
        const val AUTO_LOGOUT_MINUTES = "auto_logout_minutes"
```

and add after `savePinGateState()`:

```kotlin
    /** Inactivity auto sign-out, in minutes; 0 = never. */
    fun autoLogoutMinutes(): Int =
        prefs.getInt(Keys.AUTO_LOGOUT_MINUTES, AutoLogout.DEFAULT_MINUTES)

    fun saveAutoLogoutMinutes(minutes: Int) {
        prefs.edit().putInt(Keys.AUTO_LOGOUT_MINUTES, minutes.coerceIn(0, AutoLogout.MAX_MINUTES)).apply()
    }
```

`AuthClient.kt`: replace `logout()` (lines 145-155) with:

```kotlin
    /**
     * [reason] is shown once on the login screen when the operator did not choose to sign out
     * (inactivity); null for a deliberate logout.
     */
    fun logout(reason: String? = null, onComplete: () -> Unit = {}) {
        val payload = Schema41.envelope(Schema41.newMessageId("logout"), deviceId()).apply {
            put("operatorSessionId", OperatorSessionHolder.currentSessionIdOrEmpty())
        }
        val topic = MqttTopics.deviceRequest(deviceId(), "reader_logout_requested")
        mqtt.publish(topic, payload.toString()) { throwable ->
            if (throwable != null) Log.w(TAG, "Logout publish failed (session cleared anyway)", throwable)
        }
        OperatorSessionHolder.clear(reason)
        mainHandler.post { onComplete() }
    }
```

Existing callers (`AuthClient(this).logout()` in `MainActivity.showLogoutDialog`, `AuthClient(this).logout { … }` in `SettingsActivity`) still compile: the trailing lambda binds to `onComplete`.

- [x] **Step 6: Add SessionGuard**

Create `app/src/main/java/com/mitas/ppnam/station3aa/SessionGuard.kt`:

```kotlin
package com.mitas.ppnam.station3aa

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Process-wide owner of the inactivity auto sign-out (adapted from Station 1's SessionGuard):
 * after the configured minutes without a touch, key press or scan, the operator is signed out
 * and LoginActivity shows why (via OperatorSessionHolder.signedOutReason).
 *
 * Installed once from ScannerApp; activities only ever call [touch]. Navigation is NOT done
 * here: MainActivity and SettingsActivity already observe OperatorSessionHolder and return to
 * Login when the session is cleared while they are resumed — and the deadline can only fire
 * while one of them is resumed, because the Handler runs in the foreground and
 * onActivityResumed re-checks a deadline that passed in the background.
 *
 * Station-offline sign-out is deliberately absent: Station 3 keeps its full-screen overlay.
 */
object SessionGuard {

    private const val TAG = "SessionGuard"

    private lateinit var app: Application
    private val mainHandler = Handler(Looper.getMainLooper())
    private var monitor: InactivityMonitor? = null

    fun install(app: Application) {
        this.app = app
        monitor = InactivityMonitor(
            now = { SystemClock.elapsedRealtime() },
            schedule = { delay, r -> mainHandler.postDelayed(r, delay) },
            cancel = { r -> mainHandler.removeCallbacks(r) },
            onExpired = {
                val minutes = SettingsRepository(app).autoLogoutMinutes()
                signOut(app.resources.getQuantityString(R.plurals.signed_out_inactivity, minutes, minutes))
            },
        )

        // Start/stop the inactivity timer with the session itself.
        OperatorSessionHolder.addListener { session ->
            mainHandler.post { if (session == null) monitor?.stop() else applyTimeout() }
        }

        // A deadline that passed while the app was backgrounded is caught on the next resume.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { checkNow() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Any operator interaction or scanner read. Safe from any thread. */
    fun touch() {
        mainHandler.post { monitor?.touch() }
    }

    fun checkNow() {
        monitor?.checkNow()
    }

    /** (Re)reads the configured timeout; called when a session starts and after Settings saves. */
    fun applyTimeout() {
        if (OperatorSessionHolder.session == null) return
        val minutes = SettingsRepository(app).autoLogoutMinutes()
        monitor?.start(AutoLogout.timeoutMs(minutes))
    }

    /** Idempotent: a second trigger racing the first finds no session and does nothing. */
    fun signOut(reason: String) {
        if (OperatorSessionHolder.session == null) return
        Log.i(TAG, "Signing out: $reason")
        AuthClient(app).logout(reason)
    }
}
```

`ScannerApp.kt`: after `SessionKeeper(this).start()` (line 67) add:

```kotlin
        // Inactivity auto sign-out with a reason on the login screen (audit static-05).
        SessionGuard.install(this)
```

- [x] **Step 7: Hook the activities**

`MainActivity.kt`: change `class MainActivity : AppCompatActivity() {` to `class MainActivity : SessionActivity() {` and delete `import androidx.appcompat.app.AppCompatActivity`. In `scanReceiver.onReceive`, after `val data = intent.getStringExtra(ScannerApp.EXTRA_SCAN_DATA) ?: return` (line 77) add:

```kotlin
            SessionGuard.touch()
```

`SettingsActivity.kt`: change `class SettingsActivity : AppCompatActivity() {` to `class SettingsActivity : SessionActivity() {`, delete `import androidx.appcompat.app.AppCompatActivity`, add `import androidx.lifecycle.Lifecycle`. Add this field next to `connectionStatusListener`:

```kotlin
    /**
     * Session ended elsewhere (inactivity, expiry, station rejection) while Settings is in front:
     * go to Login with the reason. Only a non-null -> null transition counts — Settings can be
     * opened from the login screen with no session at all.
     */
    private var hadSession = false
    private val sessionListener: (OperatorSession?) -> Unit = { session ->
        runOnUiThread {
            if (session == null && hadSession && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                goToLogin()
            }
            hadSession = session != null
        }
    }
```

In `onCreate()` after `MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)` (line 40) add:

```kotlin
        OperatorSessionHolder.addListener(sessionListener)
```

In `setupSessionSection()` replace the Log Out positive-button body (lines 174-180)

```kotlin
                    AuthClient(this).logout {
                        startActivity(Intent(this, LoginActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        })
                        finish()
                    }
```

with

```kotlin
                    // sessionListener navigates to Login once the session is cleared.
                    AuthClient(this).logout()
```

Add a method after `setupSessionSection()`:

```kotlin
    private fun goToLogin() {
        if (isFinishing) return
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
```

In `onDestroy()` add `OperatorSessionHolder.removeListener(sessionListener)`.

- [x] **Step 8: Add the reason string**

In `strings.xml` after the login-error strings (Task 9) add:

```xml
    <!-- Forced sign-out reasons, shown once on the login screen -->
    <plurals name="signed_out_inactivity">
        <item quantity="one">Signed out after %1$d minute of inactivity.</item>
        <item quantity="other">Signed out after %1$d minutes of inactivity.</item>
    </plurals>
```

- [x] **Step 9: Compile and run all tests**

Run: `.\gradlew.bat :app:assembleDebug --offline` then `.\gradlew.bat :app:testDebugUnitTest --offline`
Expected: both `BUILD SUCCESSFUL`.

- [x] **Step 10: Manual verification**

The minutes field only arrives in Task 12, so set it directly: `adb -s emulator-5556 shell "run-as com.mitas.ppnam.station3aa sh -c 'cat shared_prefs/settings.xml'"` to confirm the file, then stop the app and append `<int name="auto_logout_minutes" value="1" />` inside `<map>` with `run-as … sh -c 'sed -i "s#</map>#<int name=\"auto_logout_minutes\" value=\"1\" /></map>#" shared_prefs/settings.xml'`. Relaunch, badge-login, do nothing for 60 s → Login screen with "Signed out after 1 minute of inactivity." (singular). Repeat with value 2 and tap the screen at 90 s → still signed in at 120 s, signed out at ~150 s with "…2 minutes…". Open Settings from Main, wait → Settings is replaced by Login with the reason. Press Home mid-countdown and return after the deadline → Login with the reason on return.

- [x] **Step 11: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station3aa/AutoLogout.kt app/src/main/java/com/mitas/ppnam/station3aa/InactivityMonitor.kt app/src/main/java/com/mitas/ppnam/station3aa/SessionActivity.kt app/src/main/java/com/mitas/ppnam/station3aa/SessionGuard.kt app/src/test/java/com/mitas/ppnam/station3aa/AutoLogoutTest.kt app/src/test/java/com/mitas/ppnam/station3aa/InactivityMonitorTest.kt app/src/main/java/com/mitas/ppnam/station3aa/SettingsRepository.kt app/src/main/java/com/mitas/ppnam/station3aa/AuthClient.kt app/src/main/java/com/mitas/ppnam/station3aa/ScannerApp.kt app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt app/src/main/java/com/mitas/ppnam/station3aa/SettingsActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat(session): inactivity auto sign-out with a reason on the login screen

Copies Station 1's AutoLogout/InactivityMonitor/SessionActivity/SessionGuard (tested). Closes static-05 / group (j) and the session half of S3-02 for Station 3.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 12: Settings becomes "Test & Apply" with validation, confirmation and the auto sign-out field (Tier 3 — static-06, §5 "Settings action & field set", static-26 hint, §6 Settings-form row)

**Files:**
- Modify: `app/src/main/res/layout/activity_settings.xml:381-410`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/SettingsActivity.kt` (fields, `onCreate` lines 49-56 and the save block, new methods, `onDestroy`)
- Modify: `app/src/main/res/values/strings.xml` (Settings block)

**Interfaces:**
- Consumes: `AutoLogout.parseMinutes`, `SettingsRepository.autoLogoutMinutes()/saveAutoLogoutMinutes()`, `SessionGuard.applyTimeout()` (Task 11), `hideKeyboard()` and `onSubmit` (Task 5), `MqttManager.addConnectionStatusListener/removeConnectionStatusListener/disconnect/connect` (existing).

- [ ] **Step 1: Layout — Session field, button label, status row**

In `activity_settings.xml`, after the broker password `TextInputLayout` closes (line 398, `</com.google.android.material.textfield.TextInputLayout>` before the inner card LinearLayout closes at 399) insert the Session block (copied from S1 `activity_settings.xml:400-425`, shorter hint):

```xml
                        <TextView
                            style="@style/SettingsSectionLabel"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="20dp"
                            android:text="@string/section_session_policy"
                            android:textColor="@color/primary_action" />

                        <com.google.android.material.textfield.TextInputLayout
                            android:id="@+id/tilAutoLogout"
                            style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="12dp"
                            android:hint="@string/hint_auto_logout_minutes"
                            app:boxStrokeColor="@color/outline_dark"
                            app:boxStrokeErrorColor="@color/danger"
                            app:errorTextColor="@color/danger"
                            app:hintTextColor="@color/text_secondary_dark">

                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etAutoLogout"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:imeOptions="actionDone"
                                android:inputType="number"
                                android:maxLength="4"
                                android:singleLine="true"
                                android:textColor="@color/text_primary_dark" />
                        </com.google.android.material.textfield.TextInputLayout>
```

Also add `app:boxStrokeErrorColor="@color/danger"` and `app:errorTextColor="@color/danger"` to `tilBrokerHost`, `tilBrokerPort` and `tilBrokerPassword` so validation errors render inside the fields in danger red.

Replace the Save button (lines 402-409) with the button plus a status row:

```xml
                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnSaveSettings"
                    android:layout_width="match_parent"
                    android:layout_height="56dp"
                    android:layout_marginTop="16dp"
                    android:text="@string/btn_test_apply"
                    app:backgroundTint="@color/primary_action"
                    app:cornerRadius="14dp" />

                <!-- Inline result of Test & Apply (Station 2's pattern): the operator stays here
                     and the session is kept, instead of being thrown back to Home. -->
                <LinearLayout
                    android:id="@+id/layoutApplyStatus"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:gravity="center_vertical"
                    android:orientation="horizontal"
                    android:visibility="gone"
                    tools:visibility="visible">

                    <ProgressBar
                        android:id="@+id/progressApply"
                        android:layout_width="20dp"
                        android:layout_height="20dp"
                        android:layout_marginEnd="10dp"
                        android:indeterminateTint="@color/accent_action" />

                    <TextView
                        android:id="@+id/tvApplyStatus"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:textColor="@color/text_primary"
                        android:textSize="15sp"
                        tools:text="Testing connection…" />
                </LinearLayout>
```

- [ ] **Step 2: Strings**

In `strings.xml` replace the Settings block's tail (after `label_signed_in_as`, before the PIN strings from Task 6) by adding:

```xml
    <string name="section_session_policy">Session</string>
    <string name="hint_auto_logout_minutes">Auto sign-out (minutes, 0 = never)</string>
    <string name="error_auto_logout_minutes">Enter 0–1440</string>
    <string name="error_host_required">Enter the broker host</string>
    <string name="error_port_invalid">Enter a port from 1 to 65535</string>
    <string name="error_password_store">Could not store the password securely</string>
    <string name="btn_test_apply">Test &amp; Apply</string>
    <string name="apply_testing">Testing connection…</string>
    <string name="apply_connected">Connected — settings saved</string>
    <string name="apply_failed_timeout">Could not reach the broker. Settings were saved — check the host, port and TLS, then try again.</string>
    <string name="apply_failed_rejected">The broker rejected the username or password. Settings were saved — check them and try again.</string>
    <string name="apply_failed_store">Nothing was changed: the password could not be stored securely.</string>
```

- [ ] **Step 3: Replace Save & Restart with Test & Apply in SettingsActivity**

Fields — add next to the PIN fields (Task 6):

```kotlin
    private var applyListener: ((ConnectionStatus) -> Unit)? = null
    private val applyTimeout = Runnable { finishApply(getString(R.string.apply_failed_timeout), error = true) }

    private companion object {
        /** 10 s like every other round trip, plus MqttManager's 1.5 s status debounce. */
        const val APPLY_TIMEOUT_MS = 11_500L
    }
```

In `onCreate()`, after `binding.etBrokerUsername.setText(current.username)` (line 53) add:

```kotlin
        binding.etAutoLogout.setText(settingsRepository.autoLogoutMinutes().toString())
        binding.etAutoLogout.onSubmit { binding.btnSaveSettings.performClick() }
```

Replace the whole `binding.btnSaveSettings.setOnClickListener { … }` block (originally lines 67-111) with:

```kotlin
        binding.btnSaveSettings.setOnClickListener { testAndApply() }
```

Add these methods after `setupToolbar()`:

```kotlin
    /**
     * Station 2's Test & Apply (audit static-06) with Station 1's validation: validate inline,
     * save, reconnect, and report the result here — the operator stays on Settings and keeps
     * the session instead of being relaunched to Home.
     */
    private fun testAndApply() {
        binding.tilBrokerHost.error = null
        binding.tilBrokerPort.error = null
        binding.tilBrokerPassword.error = null
        binding.tilAutoLogout.error = null

        val host = binding.etBrokerHost.text.toString().trim()
        val port = BrokerSettings.parsePort(binding.etBrokerPort.text.toString())
        val autoLogoutMinutes = AutoLogout.parseMinutes(binding.etAutoLogout.text.toString())
        var valid = true
        if (host.isBlank()) {
            binding.tilBrokerHost.error = getString(R.string.error_host_required)
            valid = false
        }
        if (port == null) {
            binding.tilBrokerPort.error = getString(R.string.error_port_invalid)
            valid = false
        }
        if (autoLogoutMinutes == null) {
            binding.tilAutoLogout.error = getString(R.string.error_auto_logout_minutes)
            valid = false
        }
        if (!valid || port == null || autoLogoutMinutes == null) return
        hideKeyboard()

        settingsRepository.saveAutoLogoutMinutes(autoLogoutMinutes)
        SessionGuard.applyTimeout()

        val typedPassword = binding.etBrokerPassword.text.toString()
        val newSettings = BrokerSettings(
            host = host,
            port = port,
            useWebSocket = binding.swBrokerWebSocket.isChecked,
            useTls = binding.swBrokerTls.isChecked,
            username = binding.etBrokerUsername.text.toString().trim(),
            // Blank field keeps the already-provisioned password: the repository only
            // writes a non-blank password to the Keystore.
            password = typedPassword.ifBlank { settingsRepository.brokerSettings().password },
        )

        binding.btnSaveSettings.isEnabled = false
        showApplyStatus(getString(R.string.apply_testing), pending = true, error = false)

        val mqtt = MqttManager.getInstance(this)
        // 1. Properly disconnect from the OLD broker first (retained presence goes offline).
        mqtt.disconnect {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 2. Save the new settings after the old presence is offline.
                if (!settingsRepository.save(newSettings)) {
                    binding.tilBrokerPassword.error = getString(R.string.error_password_store)
                    finishApply(getString(R.string.apply_failed_store), error = true)
                    mqtt.connect()
                    return@runOnUiThread
                }
                binding.etBrokerPassword.setText("")
                // 3. Reconnect against the new broker and wait for the verdict.
                awaitConnectionResult(mqtt)
                mqtt.connect()
            }
        }
    }

    private fun awaitConnectionResult(mqtt: MqttManager) {
        val listener: (ConnectionStatus) -> Unit = { status ->
            runOnUiThread {
                when (status) {
                    // STATION_OFFLINE still means the broker link is up; the station row says the rest.
                    ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                        finishApply(getString(R.string.apply_connected), error = false)
                    ConnectionStatus.BROKER_REJECTED ->
                        finishApply(getString(R.string.apply_failed_rejected), error = true)
                    ConnectionStatus.OFFLINE, ConnectionStatus.RECONNECTING -> Unit
                }
            }
        }
        applyListener = listener
        // addConnectionStatusListener fires once with the current (OFFLINE) status: ignored above.
        mqtt.addConnectionStatusListener(listener)
        ticker.postDelayed(applyTimeout, APPLY_TIMEOUT_MS)
    }

    private fun finishApply(message: String, error: Boolean) {
        ticker.removeCallbacks(applyTimeout)
        applyListener?.let { MqttManager.getInstance(this).removeConnectionStatusListener(it) }
        applyListener = null
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        showApplyStatus(message, pending = false, error = error)
        binding.btnSaveSettings.isEnabled = true
    }

    private fun showApplyStatus(message: String, pending: Boolean, error: Boolean) {
        binding.layoutApplyStatus.visibility = View.VISIBLE
        binding.progressApply.visibility = if (pending) View.VISIBLE else View.GONE
        binding.tvApplyStatus.text = message
        // Pending = primary text, success = green, failure = danger red.
        val colour = when {
            error -> R.color.danger
            pending -> R.color.text_primary
            else -> R.color.success
        }
        binding.tvApplyStatus.setTextColor(getColor(colour))
    }
```

In `onDestroy()` add, before the listener removals:

```kotlin
        ticker.removeCallbacks(applyTimeout)
        applyListener?.let { MqttManager.getInstance(this).removeConnectionStatusListener(it) }
```

Delete the now-unused `import android.content.Intent` only if the compiler reports it unused (it is still used by `goToLogin()` from Task 11, so it stays).

- [ ] **Step 4: Compile and run all tests**

Run: `.\gradlew.bat :app:assembleDebug --offline` then `.\gradlew.bat :app:testDebugUnitTest --offline`
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 5: Manual verification**

Install, badge-login, open Settings from Main, unlock. (1) Clear Host, tap Test & Apply → "Enter the broker host" inside the Host field; set Port to `0` → "Enter a port from 1 to 65535"; Auto sign-out `1441` → "Enter 0–1440"; nothing was sent. (2) Valid values (host `10.0.2.2`, port `9001`, auto sign-out `15`) → "Testing connection…" with spinner, then green "Connected — settings saved"; you are still on Settings and the Session card still shows the operator; Back → Main still logged in. (3) Port `9002` → after ~11 s red "Could not reach the broker. Settings were saved…" and the button re-enabled; pill Offline. Fix to `9001` → Connected. (4) Username `bad` → red "The broker rejected the username or password…" (Diagnostics broker row "Broker login rejected"); restore `test`. (5) Keyboard check on Username: with the keyboard up, the layout scrolls (swipe) and Test & Apply can be reached; Password → Done runs Test & Apply. (6) Auto sign-out `1`, apply, Back to Main, wait 60 s → Login with the reason.

- [ ] **Step 6: Commit**

```
git add app/src/main/res/layout/activity_settings.xml app/src/main/java/com/mitas/ppnam/station3aa/SettingsActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat(settings): Test & Apply with inline validation, result and auto sign-out minutes

Stays on Settings and keeps the session (Station 2 behaviour) with Station 1's host/port validation. Closes static-06 and the Settings rows of the consistency matrix for Station 3.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 13: Shared chrome — M3 dialogs, "Log out", gear icon, switch tints, bar colours, pill vocabulary, operator chip (Tier 3 — S3-08, S3-09, static-10, static-12, static-15, static-18, static-22, static-23, static-26)

**Files:**
- Create: `app/src/main/res/drawable/ic_settings_gear.xml`
- Create: `app/src/main/res/color/switch_track_tint.xml`
- Create: `app/src/main/res/color/switch_thumb_tint.xml`
- Modify: `app/src/main/res/values/themes.xml:24-51`
- Modify: `app/src/main/res/values/colors.xml:20-25`
- Modify: `app/src/main/res/values/strings.xml:37`
- Modify: `app/src/main/res/layout/activity_login.xml:31-38`
- Modify: `app/src/main/res/layout/activity_main.xml:44-56, 79-89`
- Modify: `app/src/main/res/layout/activity_settings.xml` (the two switches)
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/LoginActivity.kt:13, 203-210`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt` (both dialog builders)
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/SettingsActivity.kt` (logout dialog, `updateDiagnostics`)

- [ ] **Step 1: Theme — dialog overlay, bar colours, toolbar title size**

In `themes.xml` replace lines 24-33 (Status Bar + alertDialogTheme items) inside `Base.Theme.SysOneScanner` with:

```xml
        <!-- System bars: the strip above the toolbar is card-coloured like Station 2/4's
             AppScaffold; the navigation bar takes the window colour on every screen so it no
             longer flips between black and light grey (audit static-10, group (h)). -->
        <item name="android:statusBarColor">@color/card_background</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:navigationBarColor">@color/window_background</item>
        <item name="android:windowLightNavigationBar">false</item>

        <!-- One dialog style for the whole suite: Material 3 rounded, inset from the edges,
             neutral dismiss and a red destructive confirm (Station 2's look). Also used for
             AlertDialog.Builder so nothing renders on the platform's light dialog surface. -->
        <item name="materialAlertDialogTheme">@style/AppAlertDialogTheme</item>
        <item name="alertDialogTheme">@style/AppAlertDialogTheme</item>
        <item name="android:alertDialogTheme">@style/AppAlertDialogTheme</item>
```

Replace the `AppAlertDialogTheme` style (lines 38-43) with:

```xml
    <style name="AppAlertDialogTheme" parent="ThemeOverlay.Material3.MaterialAlertDialog">
        <item name="colorSurface">@color/card_background</item>
        <item name="colorOnSurface">@color/text_primary</item>
        <item name="colorOnSurfaceVariant">@color/text_muted</item>
        <item name="android:textColorPrimary">@color/text_primary</item>
        <item name="android:textColorSecondary">@color/text_muted</item>
        <!-- MaterialAlertDialogBuilder reads the panel shape and insets from alertDialogStyle,
             not from the theme's shape attributes. -->
        <item name="alertDialogStyle">@style/MaterialAlertDialog.SysOneScanner</item>
        <item name="buttonBarPositiveButtonStyle">@style/Widget.SysOneScanner.DialogButton.Destructive</item>
        <item name="buttonBarNegativeButtonStyle">@style/Widget.SysOneScanner.DialogButton.Neutral</item>
        <item name="buttonBarNeutralButtonStyle">@style/Widget.SysOneScanner.DialogButton.Neutral</item>
    </style>

    <!-- 28dp corners and a 24dp inset from the screen edges, like Station 2's Compose dialogs. -->
    <style name="MaterialAlertDialog.SysOneScanner" parent="MaterialAlertDialog.Material3">
        <item name="shapeAppearanceOverlay">@style/ShapeAppearanceOverlay.SysOneScanner.Dialog</item>
        <item name="backgroundInsetStart">24dp</item>
        <item name="backgroundInsetEnd">24dp</item>
    </style>

    <style name="ShapeAppearanceOverlay.SysOneScanner.Dialog" parent="">
        <item name="cornerFamily">rounded</item>
        <item name="cornerSize">28dp</item>
    </style>

    <!-- Positive = the destructive/irreversible action in every dialog this app shows
         (Close the app, Log out): red. Negative = neutral dismiss. -->
    <style name="Widget.SysOneScanner.DialogButton.Destructive" parent="Widget.Material3.Button.TextButton.Dialog">
        <item name="android:textColor">@color/danger</item>
        <item name="android:textAllCaps">false</item>
    </style>

    <style name="Widget.SysOneScanner.DialogButton.Neutral" parent="Widget.Material3.Button.TextButton.Dialog">
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:textAllCaps">false</item>
    </style>
```

In the `TextAppearance.SysOneScanner.ToolbarTitle` style add `<item name="android:textSize">18sp</item>` (Compose apps use titleLarge 18sp; the XML 22sp was the odd one out — static-22).

- [ ] **Step 2: Colours — switch tints, remove unused tokens**

Create `app/src/main/res/color/switch_track_tint.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="@color/primary_action" android:state_checked="true" />
    <item android:color="@color/card_background_alt" />
</selector>
```

Create `app/src/main/res/color/switch_thumb_tint.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="@color/text_primary" android:state_checked="true" />
    <item android:color="@color/text_muted" />
</selector>
```

In `colors.xml` replace lines 20-25 with:

```xml
    <!-- App identity - PPNAM icon pack app 3 (crimson): field and light tint, as in
         UI_Design\Android app logo directions\README.md. The field colour doubles as the
         primary action colour so each station app carries its launcher identity through its UI. -->
    <color name="brand_field">#7B1418</color>
    <color name="brand_tint">#E3B9BA</color>
```

(`station_field` was an unused duplicate; `brand_tint` now matches the README — static-23.)

- [ ] **Step 3: Gear icon**

Create `app/src/main/res/drawable/ic_settings_gear.xml` (the Material "settings" glyph, same shape as Compose `Icons.Filled.Settings`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z" />
</vector>
```

In `activity_login.xml` lines 31-38 change `android:src="@android:drawable/ic_menu_preferences"` to `android:src="@drawable/ic_settings_gear"` and `app:tint="@color/text_muted"` to `app:tint="@color/text_primary"` (same tint on every screen — static-12).

In `activity_main.xml` lines 79-89 change `android:src="@android:drawable/ic_menu_preferences"` to `android:src="@drawable/ic_settings_gear"` (tint already `text_primary`).

- [ ] **Step 4: Switches and operator chip**

In `activity_settings.xml` replace both `com.google.android.material.switchmaterial.SwitchMaterial` elements with `MaterialSwitch` and explicit tints:

```xml
                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/swBrokerWebSocket"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="12dp"
                            android:text="@string/label_broker_websocket"
                            android:textColor="@color/text_primary_dark"
                            app:thumbTint="@color/switch_thumb_tint"
                            app:trackDecorationTint="@color/border_primary"
                            app:trackTint="@color/switch_track_tint" />

                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/swBrokerTls"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="@string/label_broker_tls"
                            android:textColor="@color/text_primary_dark"
                            app:thumbTint="@color/switch_thumb_tint"
                            app:trackDecorationTint="@color/border_primary"
                            app:trackTint="@color/switch_track_tint" />
```

In `activity_main.xml` constrain the operator chip so a long "name · role" ellipsises instead of running under the gear (static-26): change `layoutOperator` (lines 44-56) to

```xml
            <LinearLayout
                android:id="@+id/layoutOperator"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:layout_marginEnd="8dp"
                android:background="?attr/selectableItemBackground"
                android:clickable="true"
                android:focusable="true"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:padding="4dp"
                app:layout_constrainedWidth="true"
                app:layout_constraintEnd_toStartOf="@id/btnSettings"
                app:layout_constraintHorizontal_bias="0"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@id/imgLogo">
```

- [ ] **Step 5: "Log out" casing and Diagnostics vocabulary**

`strings.xml` line 37: `<string name="btn_log_out">Log out</string>`.

`SettingsActivity.updateDiagnostics()`: change `"Disconnected"` to `"Offline"` and `"Credential rejected"` to `"Broker login rejected"` so the broker row uses the pill's words (static-18 recommended standard).

- [ ] **Step 6: MaterialAlertDialogBuilder everywhere**

`LoginActivity.kt`: replace `import androidx.appcompat.app.AlertDialog` with `import com.google.android.material.dialog.MaterialAlertDialogBuilder`; in `showExitDialog()` replace `AlertDialog.Builder(this, R.style.AppAlertDialogTheme)` with `MaterialAlertDialogBuilder(this)`.

`MainActivity.kt`: replace `import androidx.appcompat.app.AlertDialog` with `import com.google.android.material.dialog.MaterialAlertDialogBuilder`; in both `showLogoutDialog()` and `showExitDialog()` replace `AlertDialog.Builder(this, R.style.AppAlertDialogTheme)` with `MaterialAlertDialogBuilder(this)`.

`SettingsActivity.kt`: add `import com.google.android.material.dialog.MaterialAlertDialogBuilder`; in `setupSessionSection()` replace `androidx.appcompat.app.AlertDialog.Builder(this, R.style.AppAlertDialogTheme)` with `MaterialAlertDialogBuilder(this)`.

- [ ] **Step 7: Compile and run all tests**

Run: `.\gradlew.bat :app:assembleDebug --offline` then `.\gradlew.bat :app:testDebugUnitTest --offline`
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 8: Manual verification**

Install. Login: gear icon (not wrench) in `text_primary`; Back → "Close the app?" dialog has 28dp rounded corners, ≥ 24dp side inset (dump: dialog panel x1 ≥ 72 px, x2 ≤ 1008 px; the audit saw 27/1053), "Stay" in white and "Close" in red. Main: gear matches; tap the operator chip → "Log out?" with "Cancel" white and "Log out" red. Status bar strip is card-coloured on Login, Settings and Main; nav bar is `#07101A` on all three (screenshot each and compare the bottom 144 px). Settings: switch tracks are dark (`#14293D`) unchecked and crimson checked, not near-white; Diagnostics broker row reads "Offline"/"Reconnecting"/"Connected"/"Broker login rejected". Toolbar titles "Log In"/"Settings" are 18sp (≈ 63 px tall glyph box, matching Station 2). Log in with a long display name is not available on the fake backend, so check the chip constraint with `tools:text` in the layout preview or by temporarily appending text — the chip must ellipsise before the gear.

- [ ] **Step 9: Commit**

```
git add app/src/main/res/drawable/ic_settings_gear.xml app/src/main/res/color/switch_track_tint.xml app/src/main/res/color/switch_thumb_tint.xml app/src/main/res/values/themes.xml app/src/main/res/values/colors.xml app/src/main/res/values/strings.xml app/src/main/res/layout/activity_login.xml app/src/main/res/layout/activity_main.xml app/src/main/res/layout/activity_settings.xml app/src/main/java/com/mitas/ppnam/station3aa/LoginActivity.kt app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt app/src/main/java/com/mitas/ppnam/station3aa/SettingsActivity.kt
git commit -m "style(theme): M3 dialogs, gear icon, switch tints, bar colours, pill vocabulary, 'Log out'

Closes S3-08, S3-09, static-10/12/15/18/22/23/26 for Station 3.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 14: Buttons spring back after a press (Tier 3 — S3-07)

**Files:**
- Create: `app/src/main/res/values/ids.xml`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/PressFeedback.kt:21-47`
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/LoginActivity.kt` (`setLoggingIn`)
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt` (`updateScanAvailability`)

Cause: every touch event creates NEW `SpringAnimation`s on the same property, so the ACTION_DOWN spring (to 0.96) and the ACTION_UP spring (to 1.0) run concurrently and whichever settles last wins; and when a click disables the button mid-gesture, a disabled view no longer delivers touch events to the listener, so the release never arrives. Fix: one spring per property per view, retargeted with `animateToFinalPosition`, plus an explicit release when a button is re-enabled.

- [ ] **Step 1: Rewrite applyPressScaleFeedback**

Create `app/src/main/res/values/ids.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- View tag key for the cached press-feedback springs (PressFeedback.kt) -->
    <item name="press_scale_springs" type="id" />
</resources>
```

In `PressFeedback.kt` replace `applyPressScaleFeedback()` (lines 21-47) with:

```kotlin
fun View.applyPressScaleFeedback(pressedScale: Float = 0.96f) {
    if (!ValueAnimator.areAnimatorsEnabled()) return

    // One spring per axis for the lifetime of the view. Creating a fresh SpringAnimation on
    // every touch event let the press-down spring and the release spring run at the same time,
    // and whichever settled last won - which is how buttons stayed at 0.96 after a tap
    // (audit S3-07). animateToFinalPosition retargets the running spring instead.
    fun spring(property: FloatPropertyCompat<View>) = SpringAnimation(this, property).apply {
        spring = SpringForce().apply {
            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            stiffness = SpringForce.STIFFNESS_HIGH
        }
    }
    val springs = PressSprings(spring(DynamicAnimation.SCALE_X), spring(DynamicAnimation.SCALE_Y))
    setTag(R.id.press_scale_springs, springs)

    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> springs.animateTo(pressedScale)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springs.animateTo(1f)
        }
        // Let the view's normal click/ripple handling still run.
        v.onTouchEvent(event)
    }
}

/**
 * Returns a view to its resting scale. A view disabled by its own click handler (Log In while
 * the request is in flight, Select Source while a scan is pending) stops receiving touch
 * events, so its ACTION_UP never reaches the listener above; call this when re-enabling it.
 */
fun View.releasePressScale() {
    (getTag(R.id.press_scale_springs) as? PressSprings)?.animateTo(1f)
}

private class PressSprings(private val scaleX: SpringAnimation, private val scaleY: SpringAnimation) {
    fun animateTo(target: Float) {
        scaleX.animateToFinalPosition(target)
        scaleY.animateToFinalPosition(target)
    }
}
```

- [ ] **Step 2: Release on re-enable**

`LoginActivity.setLoggingIn()`: after `binding.btnLogin.isEnabled = !inFlight` add `if (!inFlight) binding.btnLogin.releasePressScale()`.

`MainActivity.updateScanAvailability()`: after `binding.btnSelectSource.isEnabled = available` add `if (available) binding.btnSelectSource.releasePressScale()`.

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual verification**

Install. Dump the UI before and after each tap and compare the button's width. Login with `manager1`/`wrong`: after the error appears, `btnLogin`'s width equals its width before the tap (the audit saw `[144,1118][936,1286]` = 792 px shrink to `[160,1176][920,1338]` = 760 px, i.e. 0.96). Main: scan `PALLET-001`; after the green card appears `btnSelectSource`'s width equals its pre-tap width (the audit saw 960 px shrink to 922 px).

- [ ] **Step 5: Commit**

```
git add app/src/main/res/values/ids.xml app/src/main/java/com/mitas/ppnam/station3aa/PressFeedback.kt app/src/main/java/com/mitas/ppnam/station3aa/LoginActivity.kt app/src/main/java/com/mitas/ppnam/station3aa/MainActivity.kt
git commit -m "fix(motion): reuse one spring per axis so buttons spring back after a press

Closes S3-07.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 15: Settings-shortcut tag only while a Station 3 screen is in front (Tier 3 — §7 item 23 / group (l) for the XML apps)

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station3aa/ScannerApp.kt:24-36`

- [ ] **Step 1: Gate the app-wide receiver on a resumed activity**

Replace lines 24-36 of `ScannerApp.kt` with:

```kotlin
    private val rfidShortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_RFID) return
            if (intent.getStringExtra(EXTRA_SCAN_DATA) != SETTINGS_RFID) return
            // The Chainway RFID broadcast reaches every station app on the device. Only act
            // while one of this app's screens is resumed (currentActivity is cleared in
            // onActivityPaused): a tag read meant for the app in front must not pull Station 3's
            // Settings over it (audit group (l)).
            val host = currentActivity ?: return
            host.startActivity(Intent(host, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
    }
```

- [ ] **Step 2: Compile**

Run: `.\gradlew.bat :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual verification**

Install. On Login, broadcast `E28011700000021B2F6E9827` → Settings opens; Back returns to Login. From Main the same opens Settings and Back returns to Main. Press Home (app in background), broadcast the tag again → nothing comes to the front (`adb -s emulator-5556 shell dumpsys activity activities | findstr topResumedActivity` is not Station 3). Reopen the app — it is where you left it.

- [ ] **Step 4: Commit**

```
git add app/src/main/java/com/mitas/ppnam/station3aa/ScannerApp.kt
git commit -m "fix(scan): ignore the Settings-shortcut tag while Station 3 is in the background

Closes the Station 3 part of audit group (l) / fix-order item 23.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 16: Final regression pass

**Files:** none modified (fixes found here go into their owning task's commit style as `fix(...)` follow-ups).

- [ ] **Step 1: Full build and tests**

```
.\gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --offline
```

Expected: `BUILD SUCCESSFUL`; test report at `app\build\reports\tests\testDebugUnitTest\index.html` shows 0 failures across BrokerSettingsTest, EditorActionsTest, PinGateTest, LoginErrorMessagesTest, AutoLogoutTest, InactivityMonitorTest and the pre-existing tests.

- [ ] **Step 2: Walk the §6 keyboard matrix rows for S3**

Install the final APK. For each row record "visible / scrolls / Enter submits":
- Login, Username (dropdown) and Password: Log In bottom ≤ 1023 with the keyboard up, also after an error line; Enter submits.
- Settings PIN gate: Unlock ≤ 1155, error text inside the field ≤ 1155, toolbar still at the top; Enter submits.
- Settings form, Host/Port/Username/Password/Auto sign-out: layout scrolls to Test & Apply with the keyboard up; Password Done and Auto sign-out Done trigger Test & Apply.
- Main, Pallet barcode or tag: Select Source ≤ 1023; Enter (Go) submits.

- [ ] **Step 3: Walk the §4 register rows S3-01..S3-10**

Re-run the repro from `station3.md` for each ID and tick it off; the expected results are each task's "Manual verification" step. Check `adb -s emulator-5556 shell logcat -d -s AndroidRuntime:E` is empty after the pass.

- [ ] **Step 4: Push the branch**

```
git log --oneline master..HEAD
git push -u origin fix/ui-audit-2026-10-02
```

Expected: 14 commits (Tasks 2-15). Open the PR from the branch; the PR description ends with the attribution lines in the Global Constraints.

---

## Self-review

**Spec coverage (Station 3 rows):**

| Finding | Task |
|---|---|
| S3-01 Select Source under keyboard | 3 |
| S3-02 Back exits silently / session kept | 8 (dialog) + 11 (inactivity sign-out) |
| S3-03 Log In under keyboard after error | 7 |
| S3-04 Enter does not submit | 5 |
| S3-05 "SCRAM proof rejected." + spell-check underline | 9 + 5 |
| S3-06 Settings adjustPan + PIN error under pad | 2 + 6 |
| S3-07 buttons stay shrunken | 14 |
| S3-08 switch tracks | 13 |
| S3-09 dialog style / "Log Out" | 13 |
| S3-10 rotation loses status; TLS default | 2 + 10 (TLS default is out of scope by the brief) |
| static-01 (S3 part) | 3 |
| static-04 Back on Home | 8 |
| static-05 session policy / reason text | 11 |
| static-06 Test & Apply + validation + minutes field | 12 |
| static-08 timeout wording (login) | 9 (scan path already uses the S3 standard + Retry) |
| static-09 values-night | 4 |
| static-10 bar colours | 13 |
| static-12 gear icon, same tint | 13 |
| static-15 dialogs | 13 |
| static-18 pill/Diagnostics vocabulary | 13 |
| static-20 password toggle (Settings already has one; blank = keep) | already compliant; Login toggle in 7 |
| static-21 (S3 inferred PIN lockout) | 6 |
| static-22 toolbar title size | 13 |
| static-23 unused `station_field`, wrong `brand_tint` | 13 |
| static-24 operator dropdown | S3 already has it (reference behaviour) |
| static-25 "Please fill in all fields" | S3 already has it (`LoginActivity.submitCredentials`) |
| static-26 `tvOperator` under the gear; long auto sign-out hint | 13 + 12 |
| §7 item 23 scan receiver gate | 15 |
| §5 Diagnostics row order | S3 already in S1 order (MQTT Broker, Station 3, Version, Device ID) |
| §5 "Close the app?" on Login | already present (`LoginActivity:97`) |

**Not covered, and why:** static-07 station-offline handling (out of scope per the brief); static-11 shapes, static-13 home chrome, static-16 motion (no recommended standard in §5 — design decisions, not defects); static-03 (Station 2 only); S3-10's TLS-on default (production default must not change); the `BROKER_REJECTED` pill colour and "Reconnecting" row colour (kept brand colour — §5 names no standard).

**Placeholder scan:** no TBD/TODO; every code step carries the code; the only "copy from S1" items (AutoLogout, InactivityMonitor, SessionActivity, SessionGuard, the Session layout block) are reproduced in full in Task 11/12.

**Type consistency:** `EditorActions.decide(Int, Int?, Int?)` and `TextView.onSubmit` (Task 5) are used unchanged in Tasks 6 and 12; `PinGate.Outcome` names match between test and activity; `AuthFailure(stage, code, message)` matches `LoginErrorMessages.kindFor`; `SettingsRepository.pinGateState()/savePinGateState()/autoLogoutMinutes()/saveAutoLogoutMinutes()` match their call sites; `AuthClient.logout(reason, onComplete)` keeps the trailing-lambda call sites compiling; `SessionGuard.applyTimeout()` is called from Task 12; `releasePressScale()` (Task 14) is only used after Task 14 defines it.

**Review Focus:** each of the five lines is pinned to a test or a manual step in its owning task (6, 5, 9, 11, 12).
