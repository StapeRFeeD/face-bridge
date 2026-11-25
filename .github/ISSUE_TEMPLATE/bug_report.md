---
name: Bug report
about: Something doesn't work as described
labels: bug
---

**What happened**


**What you expected**


**Setup**
- Headset: PICO 4 Pro / PICO 4 Enterprise
- PICO OS version (Settings → General → About):
- Face Bridge version:
- Avatar type: VRCFT v2 / VRChat built-in face tracking / none
- Status shown in Face Bridge:

**Log** (turn on Developer mode by tapping the "Face Bridge" title 7 times, reproduce, then run):

```
adb logcat -d -s VrcftBridge EyeTrackingService > facebridge.log
```

Attach `facebridge.log`.
