# Third-party notices

The PICO tracking-service access in `app/src/main/cpp/native-lib.cpp` (Binder transactions, shared-memory ring layout, PICO shape order) and `PicoTrackingSession.kt` is ported from PicoFacialBridge (https://github.com/WolalaQAQ/PicoFacialBridge), used under the MIT License:

```
MIT License

Copyright (c) 2026 WolalaQAQ and PicoFacialBridge contributors
Copyright (c) 2026 thoricelli

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

`app/src/main/cpp/filters.inc` re-implements (no code copied) techniques from:

- **VRCFaceTracking** (https://github.com/benaclejames/VRCFaceTracking), Apache License 2.0:
  Correctors (MouthClosed/JawOpen clamp, LipSuck limiter), Parameter Adjustment range floor
  (deadzone), Data Filter (One Euro filter).
- **UnifiedPicoModule** (https://github.com/WolalaQAQ/UnifiedPicoModule), MIT License,
  Copyright (c) 2026 UnifiedPicoModule contributors: PICO post-processing rules (frown/jaw
  attenuation, upper-lip press gate, smile/roll suppression, stretch competition, brow-down
  response, tongue jaw gate and hold) with their default thresholds, and the PICO→Unified
  Expressions mapping conventions used in `native-lib.cpp`.

`app/src/main/cpp/calibration.inc` re-implements (no code copied) the per-expression Lower/Upper
calibration idea from **Baballonia** by Project Babble (https://github.com/Project-Babble/Baballonia).
