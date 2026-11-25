# Power consumption — baseline measurement (v2.1, 2026-11-23)

Setup:

- **Hardware and power:** PICO 4 Pro on PICO OS 5.13.3, running on battery (USB unplugged, adb over Wi-Fi).
- **Recording:** `tools/power_sample.sh` on the headset every 5 s; analysis with `tools/analyze_power.py`.
- **Runs:** about 5 minutes per scenario, one run each.
- **Current:** the slope of the charge counter (`dumpsys battery` → Charge counter).
- **Power:** current × average voltage. This is fairer than mA, because voltage drops along with the charge.
- **CPU:** % of one core.

| Scenario | Power | Current | Charge % | Temp. °C | Bridge CPU | PICO tracking CPU | VRChat CPU |
|---|---|---|---|---|---|---|---|
| A — home screen, bridge off | **7.43 W** | 1890 mA | 87→84 | 30.0→30.5 | — | 0.9 % | — |
| B — home screen, bridge running | **8.46 W** (+1.03 W, +14 %) | 2214 mA | 83→80 | 30.5→31.5 | 1.1 % | 42.9 % | — |
| E — headset off the head (asleep), bridge running | not measurable* | — | 78→78 | 32.0 | 0.6 % | 9.1 % | — |
| C — VRChat, bridge off | **10.03 W** | 2757 mA | 76→72 | 30.5→32.2 | — | 47.4 % | 174 % |
| D — VRChat + bridge | **10.50 W** (+0.47 W, +4.7 %) | 2946 mA | 71→66 | 32.2→34.0 | 1.8 % | 48.4 % | 174 % |

\* The system does not update the charge counter while asleep. The level did not change in 5 minutes
(awake it drops 3 %). "PICO tracking" = `pxreyetrackingservice`. Head/controller tracking
(`pvrtrackingservice`) runs at 54–78 % in every scenario and does not depend on the bridge.

## Conclusions

1. **The bridge process costs almost nothing:** 1–2 % of one core. Optimizing our own code gains little.
2. **The cost is PICO's face tracking** (algorithm + IR cameras/illumination), which the bridge
   turns on. On the home screen it adds ~1 W (+14 %).
3. **In VRChat the overhead is only ~0.5 W (+4.7 %).** VRChat already keeps PICO eye tracking on
   (47 % CPU without the bridge), so the bridge mostly adds the "face" part. Roughly: 2 h of play → ~1 h 55 min.
4. **Wasted work while asleep.** With the headset off the head, the bridge tried to start tracking
   every 5 s (156 `system is sleep` refusals in the log), waking PICO's service each time (9 % CPU instead of ~0).

## What this meant for v2.2 (by benefit)

1. While asleep, don't retry every 5 s; wait for the screen to wake (`ACTION_SCREEN_ON`).
2. Don't keep tracking on while VRChat is not running (−~1 W on the home screen): automatic start/stop with VRChat.
3. Don't keep tracking on for an avatar without face tracking parameters.
4. Minor: adaptive polling instead of 60 Hz, no wake lock while paused (our process is 1–2 % anyway).
5. To investigate (risky): whether PICO can be asked for "face" only while VRChat already runs the eyes.

All of 1–4 shipped in v2.2: standby with the cameras off, no wake lock, and a native loop at 10 Hz while idle.

## Limitations

One 5-minute run per scenario. The temperature rose from scenario to scenario, and the load in VRChat
depends on the world and on what you do. The comparison error is roughly ±5 %. For an "after"
measurement, repeat C and D in the same world.
