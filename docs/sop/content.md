---
app_name: Station 3
file_stem: Station_3
station: Station 3 master batch
mark: "3"
accent: "#7B1418"
accent_tint: "#E3B9BA"
icon: images/icon.png
version: 1.5.0
date: 2026-11-06
device: Chainway C72
standfirst: >-
  How to select the master batch source pallet for Station 3 with one scan,
  read what Station 3 sends back, and what to do when it refuses a scan.
description: Standard operating procedure for the PPNAM Station 3 master batch handheld app 1.5.0 on the Chainway C72.
crop: {top: 72, bottom: 126}
revisions:
  - version: 1.5.0
    date: 2026-11-06
    change: "Suite release: visual refresh, consistency pass, scan sounds. First Station 3 handheld SOP."
---

<!-- Screenshots: v1.5.0 (7) debug build of master on the c72_clone emulator, against a
     throwaway Station 3 mock (Node + aedes broker on 1885, control on 8790). For the captures
     the mock's refusal wording was set to the Station 3 desktop's own text
     (ScannerMessageRouter.cs). Operators, pallets and products are demo data. -->

# Purpose and scope {#purpose}

The Station 3 app runs on the Chainway C72 handheld at the master batch station. Operators use it for one job: **selecting the source pallet** that master batch will be drawn from. You scan the pallet, and Station 3 selects it for you and shows its product and how many kilograms are available. You then make the packs on the Station 3 desktop.

## What it does

- Sends the pallet barcode or RFID tag you scan to Station 3, which selects that pallet as your source.
- Shows the source's product code and description, the kilograms available, and the pallet and batch.
- Tells you when a pallet cannot be used, and why.

## What it does not do

- It does not weigh, make packs or print labels. You enter the pack weight on the Station 3 desktop, signed in as the same user. Each new label deducts its weight there.
- Scanning never deducts stock. It only chooses the source.
- It keeps nothing to send later. Every scan needs Station 3 online.

> [!NOTE] Who this is for
> Master batch operators at Station 3. Sections marked **Supervisor** are for the shift supervisor, who holds the settings PIN.

# Before you start {#before}

- [ ] The handheld is charged. Put it back on its charger at the end of the shift.
- [ ] You are on the plant Wi-Fi, in coverage at the master batch station.
- [ ] You know your Station 3 username and password, or you have your RFID badge.
- [ ] The Station 3 desktop is running. The status pill shows **Connected** (section 3).
- [ ] The source pallet has its barcode label or its current RFID tag.

::: step Opening the app
shot: 01_login_empty.png
caption: The Log In screen, ready for a username or a badge.
alt: Station 3 Log In screen with Username and Password fields, a crimson Log In button, "or scan your badge" and a Connected pill
The handheld starts in the **PPNAM Station Launcher**. Tap the crimson **Station 3** tile.

The app opens on **Log In**, or straight on the Master Batch screen if you are still signed in. Wait a few seconds for the status pill in the top-right corner to settle on **Connected** before you sign in.
:::

# The status pill and header {#status}

The coloured pill in the top-right corner is on every screen. It reports two links: the **network link** (the broker that carries messages) and **Station 3 itself** (the desktop). It waits 1.5 seconds before it changes, so a short blip does not make it flicker.

::: pills
go | Connected | The network is up and the Station 3 desktop is running. Everything works. | Carry on.
warn | Reconnecting | The handheld lost the network link and is getting it back. It retries by itself, from every second up to every 30 seconds. | Wait. Scanning is paused until it clears. If it does not clear, move into Wi-Fi coverage.
warn | Station 3 offline | The network is fine, but the Station 3 desktop is not running or not reachable. A full-screen warning covers the Master Batch screen. | Wait. It clears by itself when Station 3 is back. Tell your supervisor if it lasts.
stop | Offline | The handheld is not trying to connect: it has no broker account set up yet, or a supervisor is applying new settings. | Ask your supervisor to set up the broker in Settings.
:::

A red **Broker login rejected** pill means the broker refused this handheld's broker username or password. The app stops retrying until a supervisor corrects the broker account in Settings.

::: shots
13_home_reconnecting.png | Reconnecting: scanning pauses until the link is back. | Master Batch screen with an amber Reconnecting pill, a grey Select Source button and the amber notice "Not connected to the broker. Scanning resumes when it reconnects."
12_station_offline.png | Station 3 offline: the screen is covered until it returns. | Full-screen warning "Station 3 offline" with "Wait for Station 3 to reconnect. Scanning resumes when it comes back."
02_login_offline.png | Offline: no broker account set up on this handheld. | Log In screen with a red Offline pill
:::

::: step The header
shot: 10_home.png
caption: The Master Batch screen, ready for a scan.
alt: Station 3 home with "Sam Operator · Operator", the Settings gear, a Connected pill and the Master Batch — Select Source card with a "Pallet barcode or tag" field and a Select Source button
- **Your name and role** sit under the logo. Tap them to log out (section 10).
- The **gear** opens Settings.

Below the header is the only job, **Master Batch — Select Source**. It is the home screen.
:::

::: step No workflows enabled
shot: 11_home_no_workflows.png
caption: This account may not select sources.
alt: Home for "Nora Viewer · Viewer" with the card "No workflows enabled" and "Your account has no scanner workflows at Station 3. Ask a manager to check your role."
Station 3 decides what each account may do. If yours may not select sources, the home screen shows *No workflows enabled* instead, and scans do nothing. Ask a manager to check your role on the Station 3 desktop.
:::

# Signing in {#signin}

Your source selection is recorded against your name, and the desktop expects the same user to make the packs, so the app does nothing until you sign in. You can sign in with your username and password, or with your badge.

::: step By username and password
shot: 03_login_dropdown.png
caption: Tap the arrow to pick your name from the station's list.
alt: Username dropdown open, listing "Sam Operator (sim)" and "Nora Viewer (noperm)"
1. Tap **Username**. Pick your name from the list (each row reads *Your Name (username)*) or type your username.
2. Tap **Password** and type your password. The eye icon shows what you typed.
3. Tap **Log In**, or press Enter on the keyboard.

The list comes from Station 3. A username that is not in the list still works.
:::

::: step While Station 3 checks
shot: 04_login_logging_in.png
caption: The spinner shows while Station 3 checks your password.
alt: Log In screen with username sim, a masked password and a spinner on the Log In button
A spinner replaces the button label and both fields are locked until Station 3 answers, for up to 10 seconds. Your password itself is never sent over the network.
:::

::: step By badge
shot: 06_login_badge_rejected.png
caption: A badge Station 3 does not know is refused.
alt: Log In screen with the red message "Badge not recognised. Try again or log in with your username."
While the Log In screen is showing, **scan your RFID badge**. You do not need to tap anything first.

A second badge read while a sign-in is already running is ignored, so a double read cannot sign in the wrong person. An unknown badge gives *Badge not recognised. Try again or log in with your username.* Signing in makes no scan sound.
:::

::: step If sign-in fails
shot: 05_login_error.png
caption: The reason appears in red above the fields.
alt: Log In screen with the red message "Incorrect username or password."
The reason appears in red at the top of the card. The common ones:

- **Incorrect username or password.** Retype them carefully.
- **Station 3 did not respond. Check the station and retry.** No answer within 10 seconds. Check the pill, then tap **Log In** again.
- **Not connected to the broker. Check Settings and retry.** The handheld has no network link.
- **Please fill in all fields** Both boxes are needed.

Every sign-in message is listed in section 8.
:::

## Signed out by the app

::: step Why you were signed out
shot: 07_login_signed_out.png
caption: Why you were signed out is shown on Log In.
alt: Log In screen with the red message "Signed out after 1 minute of inactivity."
- If nobody touches the handheld or scans for **15 minutes**, the app signs you out with *Signed out after 15 minutes of inactivity.* Your supervisor can change the time. Time with the app in the background counts too.
- The app checks your session with Station 3 every 30 seconds. If Station 3 has ended it, you return to Log In with Station 3's reason, usually *Sign in again.*
- When your session reaches the end time Station 3 gave it, you see *Your session expired. Sign in again.*

Station 3 going offline does **not** sign you out: the screen is covered until it returns.
:::

# Selecting a source {#workflow-source}

One scan selects one source. A new scan replaces the previous selection on Station 3.

::: step Scan the source pallet
label: Step 1
shot: 15_scan_pending.png
caption: Selecting source… until Station 3 answers.
alt: Master Batch card with PALLET-001 in the field, a grey Select Source button and a spinner with "Selecting source…"
Scan the source pallet's **barcode** or its **current RFID tag**. The scan is sent straight away: you do not need to tap anything.

You can also type the barcode into **Pallet barcode or tag** and tap **Select Source**, or press Enter.

*Selecting source…* shows while Station 3 looks the pallet up. **Select Source** is grey until it answers, for up to 10 seconds.
:::

::: step Read the source
label: Step 2
shot: 16_source_selected.png
caption: Selected. The kilograms available are what you can draw on.
alt: Green SOURCE SELECTED card with product 1500000001, MASTERBATCH BLACK, 24.5 kg available, and Pallet PALLET-001 · Batch BATCH-001
A green **Source selected** card appears and you hear the success chirp. It shows:

- the **product code** in large figures, with the product description under it;
- the **kilograms available** on that pallet;
- the **pallet** and **batch**.

Check it is the source you meant. The card clears itself after 15 seconds, and the field is empty for the next scan.
:::

::: step Make the packs on the desktop
label: Step 3
shot: 17_source_selected_long.png
caption: A long product description wraps; nothing is cut off.
alt: Green SOURCE SELECTED card with product 1500000077, MASTERBATCH TITANIUM WHITE UV-STABILISED 40% CONCENTRATE, 1,234.567 kg available, Pallet PALLET-002 · Batch BATCH-2026-11-06-A
Go to the Station 3 desktop, signed in as the **same user**, and enter the pack weight in kg there. Each new label deducts its weight from the source.

To change source, scan the other pallet. Weights show up to three decimals with thousands separators, for example `1,234.567 kg`.
:::

::: step If the source is refused
shot: 18_source_not_found.png
caption: A refused source. Scan a different pallet.
alt: Red panel "Source is not in the Station 1 replica." under the PALLET-999 scan
A red panel shows Station 3's reason and you hear the fail buzz. No source is selected. The usual ones:

- *Source is not in the Station 1 replica.* Station 3 does not know this pallet. Check you scanned the pallet's own label or tag.
- *Source stock is empty or unavailable. Wait for Central stock and source identity to sync.* The pallet has no stock Station 3 can use yet. Wait, then scan again, or choose another pallet.
- *This scan matches more than one source pallet. Call a supervisor.*

Section 8 lists every message.
:::

::: step If Station 3 cannot answer
shot: 23_scan_timeout.png
caption: No answer in 10 seconds. Retry sends the same scan again.
alt: Red panel "Station 3 did not respond. Check the station and retry." with a Retry button
*Station 3 did not respond. Check the station and retry.* or *Station could not process the request. Check database status and retry.* comes with a **Retry** button. Check the pill, then tap **Retry**: it sends the last scan again. Scanning the pallet again does the same.
:::

::: step A badge instead of a pallet
shot: 21_badge_refused.png
caption: Badges are never sent as a source.
alt: Master Batch screen with a pop-up message "That is a user badge, not a source pallet"
If you scan a staff badge, the app refuses it on the handheld with *That is a user badge, not a source pallet* and the fail buzz. Nothing is sent.
:::

::: step When scanning is paused
shot: 22_scan_not_allowed.png
caption: An amber notice says why scanning is paused.
alt: Amber panel "Your account cannot select source stock at Station 3." under a grey Select Source button
An amber panel means scanning is paused, and **Select Source** is grey:

- *Not connected to the broker. Scanning resumes when it reconnects.* Wait for **Connected**.
- *Station 3 is offline. Scanning resumes when it comes back.* Wait for **Connected**.
- *Your account cannot select source stock at Station 3.* Station 3 refused your account. Ask a manager to check your role, then sign in again.

A scan while paused is refused with the fail buzz and is not sent.
:::

# Scan sounds and vibration {#sounds}

Every PPNAM handheld uses the same two sounds. The Chainway scanner beeps by itself when it reads anything; the app's sound comes **after** that, when the answer is known.

- **Success chirp** (a bright rising two-note chirp) with one short 40 ms vibration: the scan was **accepted**.
- **Fail buzz** (a low falling double buzz) with two 200 ms vibrations: the scan **failed**. Read the screen.

The sounds play at the **media volume**. If you cannot hear them, turn the media volume up; the vibration still tells you.

Table: What you hear in the Station 3 app
| When | Outcome | Sound |
|---|---|---|
| Master Batch | Station 3 selects the source | {ok} |
| Master Batch | Source refused, account not allowed, or Station 3 could not process it | {fail} |
| Master Batch | No answer in 10 seconds, or the scan could not be sent | {fail} |
| Master Batch | A staff badge read instead of a pallet | {fail} |
| Master Batch | A scan while scanning is paused (offline, not allowed, or still waiting) | {fail} |
| Master Batch | A typed value that is empty, too long or has hidden characters | {fail} |
| Master Batch | Another read of the pallet already being looked up | {none} |
| Log In, Settings, buttons, dialogs | Signing in, tapping, the 30-second session check | {none} |

## The repeat-read rule

One pull of the trigger can read the same code several times. A repeat of a value that was **just refused on the handheld**, within 1.5 seconds, makes no sound, so holding the trigger never buzzes twice. A different value plays its own sound. A repeat read of the pallet Station 3 is already looking up is the same scan: it sounds once, on Station 3's answer.

# Messages and what to do {#messages}

Every message the app shows, word for word. Station 3 writes its own refusal reasons; the ones it uses today are listed by where they appear. *Ask a manager* or *Ask a supervisor* means the problem cannot be fixed on the handheld.

Table: Signing in
| Message | What it means | What to do |
|---|---|---|
| Please fill in all fields | The username or password box is empty. | Fill in both. |
| Incorrect username or password. | Station 3 did not accept the password. | Retype carefully. Ask your supervisor if you are locked out. |
| Badge not recognised. Try again or log in with your username. | The badge is not registered at Station 3. | Scan again, or sign in with your username and ask a manager to register the badge. |
| Login was refused by the station (*code*). Ask a supervisor. | Station 3 refused for another reason. | Tell your supervisor the code. |
| Station 3 could not complete the login. Try again. | Station 3's answer could not be used. | Try again. Report it if it repeats. |
| Not connected to the broker. Check Settings and retry. | The handheld has no network link. | Check the pill and Wi-Fi, then retry. |
| Station 3 did not respond. Check the station and retry. | No answer within 10 seconds. | Check that the Station 3 desktop is running, then retry. |
| Signed out after *N* minutes of inactivity. | The automatic sign-out ran. | Sign in again. |
| Sign in again. / Session ended. Sign in again. | Station 3 ended your session. | Sign in again and scan again. |
| Your session expired. Sign in again. | The session reached its end time. | Sign in again. |

Table: Selecting a source
| Message | What it means | What to do |
|---|---|---|
| Selecting source… | Waiting for Station 3. | Wait. |
| Source selected | Station 3 selected the pallet. | Make the packs on the desktop. |
| That is a user badge, not a source pallet | A staff badge was read. | Scan the pallet. |
| Scan a barcode or tag of up to 500 characters. | The typed value is empty, too long or has hidden characters. | Scan the label, or retype it. |
| Still waiting for the previous scan. | A scan is already being looked up. | Wait for the answer. |
| Source is not in the Station 1 replica. | Station 3 does not know this pallet. | Check the label or tag. Ask a supervisor if it is the right pallet. |
| Source stock is empty or unavailable. Wait for Central stock and source identity to sync. | The pallet has no usable stock yet. | Wait and scan again, or use another pallet. |
| Source stock changed or is unavailable. Scan again after checking the replica. | The stock changed during the scan. | Scan again. |
| This scan matches more than one source pallet. Call a supervisor. | Two pallets share this code. | Call a supervisor. |
| Scan a valid source barcode or tag. / Scan a source barcode or current RFID tag. | Station 3 could not use the value. | Scan the pallet's label or current tag. |
| Station 3 did not respond. Check the station and retry. | No answer within 10 seconds. | Check the pill, then **Retry**. |
| Station could not process the request. Check database status and retry. | The Station 3 database had a problem. | **Retry**. Tell your supervisor if it repeats. |
| Not connected to the broker | The scan could not be sent. | Wait for **Connected**, then **Retry**. |
| Could not reach Station 3 | The scan could not be sent. | Check the pill, then **Retry**. |
| Station 3 sent an incomplete source | Station 3's answer was missing values. | **Retry**. Report it if it repeats. |
| Sign in before scanning | No one is signed in. | Sign in. |
| Not connected to the broker. Scanning resumes when it reconnects. | The network link is down. | Wait for **Connected**. |
| Station 3 is offline. Scanning resumes when it comes back. | The Station 3 desktop is not running. | Wait for **Connected**. |
| Your account cannot select source stock at Station 3. | Station 3 does not allow your account to select sources. | Ask a manager to check your role, then sign in again. |
| Station 3 offline / Wait for Station 3 to reconnect. Scanning resumes when it comes back. | The full-screen warning while Station 3 is offline. | Wait. It clears by itself. |
| No workflows enabled / Your account has no scanner workflows at Station 3. Ask a manager to check your role. | Your account has no Station 3 jobs. | Ask a manager. |

Table: Settings
| Message | What it means | What to do |
|---|---|---|
| Enter the supervisor PIN. | The PIN box is empty. | Supervisor only. |
| Incorrect PIN. *N* attempts left before lockout. | Wrong PIN. | Supervisor only. |
| Too many attempts. Try again in *N*s. | Five wrong PINs lock the box for 30 seconds. | Wait for the countdown. |
| Enter the broker host / Enter a port from 1 to 65535 / Enter 0–1440 | A field is empty or out of range. | Supervisor: correct the field. |
| Could not store the password securely / Nothing was changed: the password could not be stored securely. | The handheld could not save the password. | Retry; report it if it repeats. |
| Saved, but could not connect to *host*:*port*. Check the broker settings. | The new settings were saved but do not connect. | Supervisor: check the values. |
| The broker rejected the username or password. Settings were saved — check them and try again. | Wrong broker account. | Supervisor: correct the broker username or password. |

# Settings {#settings}

The gear opens Settings from the Log In and Master Batch screens. The top half is open to everyone and is what support will ask you to read out. The bottom half needs the supervisor PIN.

::: step Diagnostics: anyone
shot: 40_settings_diag.png
caption: Diagnostics, and the PIN gate below.
alt: Settings Diagnostics: MQTT Broker Connected, Station 3 Online, Version v1.5.0 (7), Device ID scanner_59fcb3c4129d, and "Enter supervisor PIN to edit settings"
- **MQTT Broker**: the network link on its own (Connected, Reconnecting, Offline).
- **Station 3**: the desktop (Online, Offline, Unknown). Broker *Connected* with Station 3 *Offline* means the network is fine and the station is the problem.
- **Version**: `v1.5.0 (7)` for this release.
- **Device ID**: this handheld's identity, such as `scanner_59fcb3c4129d`. Read it out when you report a fault.
- **Session** at the bottom shows who is signed in, with **Log out**.
:::

> [!SUPERVISOR] Configuration
> Type the supervisor PIN and tap **Unlock**. Five wrong attempts lock the PIN box for 30 seconds. Never tell an operator the PIN; operators should ask the supervisor. A supervisor's Settings RFID tag also opens Settings from any Station 3 screen; the PIN is still needed.

::: step Unlocked: supervisors only
shot: 41_settings_unlocked.png
caption: Unlocked, showing the standard broker settings.
alt: Unlocked Settings showing Port 443, Use WebSocket on, Use TLS on, a broker Username, an empty Password field and Auto sign-out (minutes, 0 = never) 15
- **Host** and **Port**: normally `mqtt.sysone.co.za` and `443`.
- **Use WebSocket** and **Use TLS**: both on for the standard setup.
- **Username** and **Password**: the broker account, not an operator account. The saved password is never shown; leave the box blank to keep it.
- **Auto sign-out (minutes, 0 = never)**: 15 by default, up to 1440.

A new handheld has no broker password and stays **Offline** until one is saved here once.
:::

::: step Test & Apply
shot: 42_settings_applied.png
caption: Connected — settings saved.
alt: Settings with "Connected — settings saved" in green above the Test & Apply button
**Test & Apply** saves the settings, then reconnects with them while *Testing connection…* shows. The operator stays signed in.

- *Connected — settings saved*: done.
- *Saved, but could not connect to host:port. Check the broker settings.* or *The broker rejected the username or password. Settings were saved — check them and try again.*: correct the values and tap **Test & Apply** again.
:::

# Finishing up {#finishing}

::: step Handing the handheld over
shot: 45_logout_dialog.png
caption: Log out asks first.
alt: Log out? dialog reading "You'll need to log in again to continue." with Cancel and a red Log out button
1. Finish the packs for your source on the desktop.
2. Tap your name in the header (or **Log out** in Settings).
3. Confirm **Log out**.

Always log out before you pass the handheld to someone else. Otherwise their source selection is recorded under your name.
:::

::: step Closing the app
shot: 46_exit_dialog.png
caption: Back from Log In or the Master Batch screen.
alt: Close the app? dialog reading "You'll leave PPNAM Station 3 and return to the home screen." with Stay and Close
From the Log In or Master Batch screen, the **Back** key asks *Close the app?*. Tap **Close** to return to the Launcher, or **Stay**.

At the end of the shift, log out and put the handheld on its charger.
:::

# Troubleshooting {#troubleshooting}

Table: Common problems
| Problem | Likely cause | What to do |
|---|---|---|
| The screen is covered by **Station 3 offline**. | The Station 3 desktop is off, or its program is not running. | Wait. Tell your supervisor if it lasts. |
| The pill stays **Reconnecting**. | No Wi-Fi where you are. | Move into coverage. If it stays, your supervisor checks Settings. |
| The pill is red **Offline** on a new handheld. | No broker account has been saved. | Your supervisor sets it up in Settings. |
| The pill is red **Broker login rejected**. | The broker account in Settings is wrong. | Your supervisor corrects it in Settings. |
| *No workflows enabled* after sign-in. | Your account has no Station 3 jobs. | Ask a manager. |
| **Select Source** stays grey. | Scanning is paused: read the amber notice. | Wait for **Connected**, or ask a manager about your role. |
| Every scan says *Source is not in the Station 1 replica.* | Wrong label, or the pallet has not reached Station 3's copy of Station 1 stock. | Check the label. Tell your supervisor. |
| The source card disappeared. | It clears itself after 15 seconds. | Scan the pallet again to see it. |
| No scan sounds. | Media volume is down. | Turn up the media volume. The vibration still works. |
| Signed out unexpectedly. | 15 minutes without a touch or scan, or Station 3 ended the session. | Read the message on Log In and sign in again. |

> [!NOTE] Reporting a fault
> Give your supervisor the **Device ID** and **Version** from Settings, the time, the pallet barcode, and the exact message on screen.
