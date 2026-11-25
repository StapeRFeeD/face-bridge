# Changelog

## 2.3.0 — first public release

- **New app ID `io.github.staperfeed.facebridge`** (was `com.pico.vrcft.standalone`, which looked like an
  official PICO package). It installs as a new app: uninstall the old one
  (`adb uninstall com.pico.vrcft.standalone`), then grant the permissions again. Settings and
  calibration start fresh.
- MIT license.
- All documentation is in English: a new README, [docs/TECHNICAL.md](docs/TECHNICAL.md),
  the roadmap and the power measurements.
- Documentation screenshots without the headset optics:
  `adb shell am start -n io.github.staperfeed.facebridge/.AdbControl --ez screenshots true`.
- Optional release signing through `keystore.properties` (see [docs/RELEASING.md](docs/RELEASING.md)).

## 2.2.3 — security hardening

- The adb `autostart` / `calibrate` extras only work through the DUMP-protected `.AdbControl` alias.
  Before this, any app on the headset could start tracking or calibration.
- OSCQuery only accepts VRChat services on this headset, caps responses at 4 MB and the parameter
  tree at 32 levels, and survives hostile JSON. Before this, a device on the LAN could pose as VRChat.
- Native: shutdown waits for the loop before closing the socket, the status text is thread-safe
  ASCII, setters are NaN-safe, and JNI string checks were added.
- The service interrupts its supervisor on stop. The UI cancels pending callbacks and animators on destroy/detach.

## 2.2.2

- The Smile calibration step says teeth may show but the jaw stays closed. The mascot grins
  with teeth and no tongue.

## 2.2.1

- The calibration face is a friendly 2D mascot instead of a soft-3D face that read as uncanny.
- Calibration is one centered column, so you look straight ahead.

## 2.2 — battery

- Tracking only runs while needed: the headset is awake, VRChat is running (OSCQuery or OSC port
  9000 in use), the avatar has face tracking parameters, or calibration is open. Otherwise the
  bridge stands by with the cameras off (StopAlgorithm), no wake lock and the native loop at 10 Hz.
- No 5-second retry loop while asleep; the bridge reconnects on screen-on.
- Rounded app window, a launcher icon in the PICO OS 5 style, and a calibration screen in the OS 5 look.
- Interface sounds removed (they doubled the system click sounds); calibration cues kept.
- Removed the unused OSC 9001 listener and the external-buffer data source.

## 2.1

- PICO spatial design guidelines: a 32 dp window radius with smoothed corners, role-based pill buttons,
  the PICO type scale, side navigation with spring selection, confirmation dialogs, Bezier + spring motion.

## 2.0

- PICO OS style interface: sidebar navigation and a rounded content panel, PICO Sans, PICO
  colour and shape tokens.

## 1.4

- The original minimal interface (git tag `v1.4-original-ui`).
