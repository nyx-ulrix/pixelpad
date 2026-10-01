# PixelPad

Use a tablet or phone with a pen as a PC **trackpad**, **drawing tablet**, **controller** and **slide clicker**.
A retro pastel pixel app on the tablet, and a small companion, **PixelPad Desk**, on the PC.

- **Stylus first, fingers welcome.** The tablet drawing area is pen only; everywhere else fingers work.
- **Real input on the PC.** The pen shows up in Windows as a real pen (pressure, tilt, hover, eraser). Two or more fingers are sent as real touch, so Windows' own trackpad gestures work as you have them set up. The controller shows up as a DualShock 4.
- **Three ways to connect:** USB cable, Wi-Fi, or Bluetooth tethering.

## Get it running

You need a Windows 10 (1809 or newer) or Windows 11 PC and an Android 7+ tablet or phone.

**Download:** the [latest release](https://github.com/nyx-ulrix/pixelpad/releases/latest) has `PixelPad-1.2.3.apk` (install it on the tablet; it is signed with the standard debug key, so Android will ask you to allow installs from unknown sources) and `PixelPadDesk-1.2.3.exe` (run it on the PC, no install needed), plus `SHA256SUMS.txt`. Or build both yourself:

**PC (PixelPad Desk)**
```
pip install -r server/requirements.txt
pythonw server/pixelpad_desk.py
```
(`pythonw` runs it without a console window.) Or build the single-file exe, see [Build](#build). It lives in the system tray; closing the window keeps it running, and Settings has "Start with Windows".
Controller mode also needs the [ViGEmBus](https://github.com/nefarius/ViGEmBus/releases) driver.

**Tablet**
```
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Connect

Open PixelPad Desk, then open the app on the tablet. If no PC answers, the QR scanner opens by itself.
- **Wi-Fi:** scan the QR code shown in PixelPad Desk and name the PC (PixelPad Desk suggests the name you set in its Settings). The tablet keeps every PC you have paired under the name you picked, and you switch between them in **Settings > Connection**. Addresses are never shown on either side; if you can't scan, **Add a PC by its address** takes it once and then hides it.
- **USB:** plug in with USB debugging on and choose the cable in **Settings > Connection**. PixelPad Desk sets the link up by itself (it needs `adb`, from the Android platform tools).
- **Bluetooth:** turn on Bluetooth tethering on the tablet, connect to it from the PC, then scan the QR code.
- PixelPad Desk uses the first free port from 7777 up, and the QR code carries it.
- **Pairing:** the QR code also carries a pairing key. From 1.3.0 every packet is signed and encrypted with it (see below), so a device that hasn't scanned the code can't control the PC. If you can't scan, type the **pairing code** shown under the QR code (Settings > Connection > Add a PC by its address; for USB, the pairing code box on that page). **New pairing code** in PixelPad Desk's Settings replaces it, and every device then scans again.
- Can't connect? The Connection page and PixelPad Desk both have a checklist that says which step fails. On Windows, allow PixelPad Desk through the firewall on **private** networks.

## Screens

- **Trackpad:** pen or finger to move, tap to click, two-finger tap to right-click. Movement is sent raw, so Windows' pointer speed applies. Extra fingers go to Windows as touch.
- **Tablet:** the pen reaches the **whole PC screen**. Choose which part of the tablet the pen uses in **Settings > Tablet**. Pressure curve, tilt, hover range, smoothing, rotation and flip are there too. By default only the pen draws; **What draws** lets a finger draw too (it starts on for devices that report no stylus), and **Palm rejection** can be switched off.
- **Controller:** PlayStation, Xbox, Switch (full, like a Pro Controller, or half, a single Joy-Con held sideways) and fighting-pad templates (all appear as a DualShock 4). Buttons are sized to fill the screen without crowding it, so the same layout suits a phone and a tablet. Move and resize any control. Up to four tablets give four controllers.
- **Present:** a clicker with previous/next and a pointer area. The volume keys turn slides. The trackpad and presenter screens can be rotated to portrait (the rotate button at the bottom right, or **Settings > Trackpad > Screen orientation**).
- **Lock:** on any screen, double-tap the lock icon (top left). The screen goes dark and minimal, the top bar stops responding, and you keep the pen, your tablet buttons, the controller or the prev/next buttons.
- **Exit** is the power button at the top left: tap it twice. The app pins itself to the screen so Android's own swipes can't pull you out; Android shows its own message about this and locks the screen when you leave, unless you turn off "Lock device when unpinning" in Android's screen pinning settings. Settings > Trackpad turns pinning off.

## Colours

Pick the app's theme colour in **Settings > App**, from the standard Nintendo Switch colours. It is that device's colour: the app sends it to PixelPad Desk, which shows it next to the device's player number, and the PC never assigns or changes it. A new install starts with one of the four player colours picked at random. PixelPad Desk's own background colour is in its Settings.

## Your own buttons, pen buttons and gestures

- **Tablet keys:** as many buttons as you like on the tablet screen, each mapped to a shortcut, a click or the eraser. These also work while locked. A left, right or middle click key makes the pen itself act as that mouse button while you hold it (or for the next stroke if you just tap it).
- **Pen buttons:** press each button on your pen to record it, name it, and choose what it does (a shortcut, a click, the eraser, drawing a gesture, or switching screen).
- **Gestures:** record a pen shape and map it to anything.
- **Record a shortcut from your PC keyboard:** when picking an action, choose "Record from my PC keyboard...", then press the shortcut on the PC. This is off until you turn it on in PixelPad Desk's Settings (see below).

## Updates

On start, both the app and PixelPad Desk check GitHub for a newer release and ask whether to update (the app asks once per version); both also have a version and update section in Settings with a progress bar. The app downloads the APK and hands it to Android's installer (Android asks you to confirm). PixelPad Desk downloads the new exe next to itself, checks it against the release's `SHA256SUMS.txt`, swaps it in and restarts.

## A note on safety

PixelPad Desk lets a paired device move the mouse, type and press shortcuts on your PC, so it only accepts packets from devices that scanned its QR code.

- **What is protected (1.3.0 and later).** The QR code carries a random 128-bit pairing key. Every packet, in both directions, is sent in a frame that is encrypted and signed with it (HMAC-SHA256, new for each packet, with a per-run nonce and a replay window). Someone on the same public Wi-Fi, on your Bluetooth tether, or another app or program using the USB tunnel can't send input, read what you do, or replay a recording of it. Packets that fail the check are dropped before anything is created, so they can't take a player slot either. The app only believes replies from the PC's address and port, and only sealed ones.
- **What is not.** Whoever sees the QR code or the pairing code can pair, so don't share screenshots of them (the code is not drawn as text in the app, and the address is never shown). If it leaks, use **New pairing code**. Frames are not hidden from traffic analysis: someone on the network can still see that the two devices are talking, and how often. The key is stored in the app's private storage and in PixelPad Desk's settings file; backups of the app are off.
- **Old apps.** Versions before 1.3.0 don't have the key. PixelPad Desk refuses them (it says so in its log and checklist) until you update the app, or turn on **Allow old apps without pairing (not safe)** in Settings. An app that has no key because you scanned an old PixelPad Desk's QR code still works with that old Desk.
- **Ports.** The USB link listens on `127.0.0.1` only, and the Wi-Fi/Bluetooth link on UDP; both need the pairing key. Allow PixelPad Desk through the Windows firewall on **private** networks only.
- **Recording shortcuts** ("Controller devices may record shortcuts" in the Desk's Settings) is off every time the Desk starts: it listens to this PC's keyboard, so turn it on only while you need it.
- **Updates** are downloaded only from this project's own GitHub releases, and PixelPad Desk refuses an exe that doesn't match the release's `SHA256SUMS.txt`. Releases are not yet code-signed with a dedicated key (the APK uses the standard Android debug key and the exe has no Authenticode signature), so the integrity of an update rests on your GitHub account and TLS.

The exe is not code-signed, so Windows SmartScreen or your antivirus may warn about it the first time. The source is all here.

## Build

```
# tablet (needs the Android SDK, platform 35, and JDK 17)
cd android && ./gradlew assembleDebug

# PC exe
pip install -r server/requirements.txt pyinstaller
cd server && python -m PyInstaller --noconfirm PixelPadDesk.spec     # -> server/dist/PixelPadDesk.exe

# protocol self-check (fakes the Windows input calls, touches nothing)
python server/test_server.py
```
`assets/make_icon.py` redraws the app icon (needs Pillow).

## How it talks

16-byte packets: UDP over Wi-Fi / Bluetooth, TCP over USB (through `adb reverse`). The tablet smooths the pen with a One Euro filter and the PC replays the samples with the spacing the tablet measured. See `server/pixelpad_server.py` and `android/app/src/main/java/com/pixelpad/app/Sender.kt`.

## Licence

[MIT](LICENSE). PixelPad uses open-source libraries (zxing, vgamepad, segno, pystray, Pillow and others) under their own licences.
