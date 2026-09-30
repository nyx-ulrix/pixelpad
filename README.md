# PixelPad

Use a tablet or phone with a pen as a PC **trackpad**, **drawing tablet**, **controller** and **slide clicker**.
A retro pastel pixel app on the tablet, and a small companion, **PixelPad Desk**, on the PC.

- **Stylus first, fingers welcome.** The tablet drawing area is pen only; everywhere else fingers work.
- **Real input on the PC.** The pen shows up in Windows as a real pen (pressure, tilt, hover, eraser). Two or more fingers are sent as real touch, so Windows' own trackpad gestures work as you have them set up. The controller shows up as a DualShock 4.
- **Three ways to connect:** USB cable, Wi-Fi, or Bluetooth tethering.

## Get it running

You need a Windows 10 (1809 or newer) or Windows 11 PC and an Android 7+ tablet or phone.

**Download:** the [latest release](https://github.com/nyx-ulrix/pixelpad/releases/latest) has `PixelPad-1.1.0.apk` (install it on the tablet; it is signed with the standard debug key, so Android will ask you to allow installs from unknown sources) and `PixelPadDesk-1.1.0.exe` (run it on the PC, no install needed), plus `SHA256SUMS.txt`. Or build both yourself:

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
- Can't connect? The Connection page and PixelPad Desk both have a checklist that says which step fails. On Windows, allow PixelPad Desk through the firewall on **private** networks.

## Screens

- **Trackpad:** pen or finger to move, tap to click, two-finger tap to right-click. Movement is sent raw, so Windows' pointer speed applies. Extra fingers go to Windows as touch.
- **Tablet:** the pen reaches the **whole PC screen**. Choose which part of the tablet the pen uses in **Settings > Tablet**. Pressure curve, tilt, hover range, smoothing, rotation and flip are there too.
- **Controller:** PlayStation, Xbox and fighting-pad templates (all appear as a DualShock 4). Button sizes follow the screen, so the same layout suits a phone and a tablet. Move and resize any control. Up to four tablets give four controllers.
- **Present:** a clicker with previous/next and a pointer area. The volume keys turn slides.
- **Lock:** on any screen, double-tap the lock icon (top left). The screen goes dark and minimal, the top bar stops responding, and you keep the pen, your tablet buttons, the controller or the prev/next buttons.
- **Exit** is the power button at the top left: tap it twice. The app pins itself to the screen so Android's own swipes can't pull you out; Android asks about this the first time, and Settings > App turns it off.

## Your own buttons, pen buttons and gestures

- **Tablet keys:** as many buttons as you like on the tablet screen, each mapped to a shortcut, a click or the eraser. These also work while locked.
- **Pen buttons:** press each button on your pen to record it, name it, and choose what it does (a shortcut, a click, the eraser, drawing a gesture, or switching screen).
- **Gestures:** record a pen shape and map it to anything.
- **Record a shortcut from your PC keyboard:** when picking an action, choose "Record from my PC keyboard...", then press the shortcut on the PC. This is off until you turn it on in PixelPad Desk's Settings (see below).

## A note on safety

PixelPad Desk listens for the tablet on your network. Use it on networks you trust, and allow it through the Windows firewall for **private** networks only. There is no pairing password yet, so anyone on the same network could send it input while it is running. "Tablet may record shortcuts" is off by default for this reason: turn it on only while you need it.

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
