# PICO design code (reference for Face Bridge)

Collected 2026-11-23 from PICO OS 5.13.3 system apps on the headset (Library `com.pvr.appmanager`,
Motion Tracker `com.pvr.swift`, Settings): measurements from screenshots (not included in the
repository) and resource values dumped with `aapt2`.

## Typography
- **PICO Sans** — system default `sans-serif` (`/system/fonts/PICOSans.ttf`, variable weight 100–900).
  Use the default typeface with explicit weights; nothing needs bundling.
- Page title 24sp bold (Library header); dialog title 20sp; list/menu item 16sp; secondary 14sp;
  captions 12–13sp.

## Color
| Token | Value | Source |
|---|---|---|
| Sidebar / window | `#101010` | sampled (Library) |
| Content panel | `#171717` | sampled; `TabItem_Bg_Select #18181B` |
| Selected row / field | `#1D1D1D` – `#242424` | sampled |
| Dialog surface | `#333333` | sampled |
| Secondary button | `#434343` | sampled (Cancel) |
| Text primary / normal | `#FFFFFF` / `#EEEEEE` | `text_color`, `btn_text_normal` |
| Text secondary | `#A6A6A6` | `sub_text_color`, `color_text_grayA6` |
| Text disabled | `#656565` | `btn_text_disable` |
| Accent blue | `#3D8BFF` (pressed `#1B88EE`) | `osui_color_blue_primary_100`, `osui_config_color_blue` |
| Error red | `#F44336` | `osui_config_color_red` |
| Overlay | `#B3000000` | `Common_Bg_black_a70` |

## Shape & layout
- Two-pane: left navigation (icon + label rows, selected row lighter) + rounded content panel
  (`main_content_corner_radius` 28dp).
- Radii: list rows 16dp (`linear_item_radius`), menu/option 8dp, buttons 12dp
  (`osui_alert_dialog_button_radius`), dialogs 24dp, chips/search pill.
- Buttons are filled rounded rectangles: blue primary, gray secondary; no outlines.
- Rows: label left, value/control right, generous height (48–56dp+), no heavy dividers.
- Icons: simple monochrome outline glyphs, ~24dp, light gray; white when selected.

---

# PICO design guidelines (spatial design) — applied in v2.1

Source: https://developer.picoxr.com/document/spatial-design/ — all 52 pages read on 2026-11-23
(Foundation: Unit, Typesetting, Icon, Window, Animation effects, Sound; ~35 Component pages;
Typeface; Input and interaction). Spec images inspected for Button, Switch, Side Navigation, List,
Dialog Alert, Window and icons. The guidelines target PICO OS 6 (light glass material); Face Bridge
runs on PICO OS 5.13 and, by the user's choice, keeps a dark theme while applying every other rule.

| Guideline | Value | Face Bridge |
|---|---|---|
| Window corner radius | fixed 32 dp | 32 dp (dialogs, calibration card); inner panels concentric 24 dp (8 dp inset), selection 16 dp |
| Corner shape | "rounded corners reduce visual distraction", warm & natural | continuous-curvature smoothing 60 % (`SmoothRect`, Figma construction) everywhere |
| Buttons | fully rounded; roles Primary / Auxiliary / Accessible (green "start") / Other / Borderless + Danger; states normal-hover-pressed-disabled | pills by role: Start = accessible lime, Calibrate = primary (light on dark), auxiliary gray, Reset = danger + Dialog Alert confirmation; animated hover/press backgrounds |
| Interactive hotspot | 56 × 56 dp; switch 40 × 36 dp; option row 48 dp | nav rows and list items 56 dp; switch ≥ 40 × 36; radio options 48 dp |
| Side Navigation | leading edge, 272 / 316 dp (316 for 1280 dp windows), fixed header | 316 dp when window ≥ 1200 dp else 272; title header; spring-animated selection |
| Type scale | Headline Bold 24/30 −0.48px; Title Semibold 20/26, 16/20, 14/18; Label Semibold 14/18, 12/16; Body **Medium** 16/20(24), 14/18(22), 12/16; min 12 sp | `PicoStyle.Type` with line height + tracking; body text Medium |
| Disabled | grayed out, 30 % opacity | `setEnabledLook` → 0.3 alpha, animated |
| List | items in a group separated by 1 dp divider | grouped rows, 1 dp dividers |
| Dialog Alert | centered, parent dimmed, pill actions | 32 dp smoothed card, dim 0.55, scale-in with gentle curve |
| Motion | small feedback < 300 ms fast easing; large 300–800 ms gentle easing; spring for direct manipulation; cubic-bezier for properties; minimal Z | 160/240 ms `cubic-bezier(0.3,0,0.1,1)`; pages 360 ms `cubic-bezier(0.2,0,0,1)`; exits `cubic-bezier(0.4,0,1,1)`; spring (ζ 0.72) for nav highlight, press release, slider value; no Z motion |
| Sound | light & simple, warm, natural; operation sounds 0.5–1.5 s; click / feedback / status (enable/disable, success); **no hover sound** | `tools/make_sounds.py` → tap, nav, toggle on/off, success, calibration step start/end (48 kHz); never on hover; switch in Status → General |
| Icons | rounded, 2 dp strokes | outline nav icons, round caps/joins |
| Color | no highly saturated blocks in dark environments | lime softened (#B9E86A), used only for the Start pill |

## v2.2 changes
- Interface sounds removed (they doubled PICO's system click sounds); only calibration cues remain
  (hold start / hold end / done — needed on the eyes-closed step).
- App window has rounded corners: transparent translucent window theme (`FaceBridge.Window`) + the root
  view drawn as a 32 dp smoothed panel. PICO composites 2D panels with alpha, so this works on OS 5.
- Launcher icon redrawn in the **PICO OS 5 dock style** (`tools/make_icon.py`, preview `docs/icon-preview.png`):
  wide 228×192 canvas, ~20 % radius, saturated gradient background, one soft 3D glyph with shadow —
  matching the system icons (Friends, Store, Library, Files).
- Calibration screen in the **OS 5 look** (Motion Tracker setup flow, system dialogs): one centered column
  (looking straight ahead), the expression prompt inside a circular progress ring, 17-segment progress bar,
  OS 5 rounded-rectangle buttons (12 dp, blue primary / gray secondary).
- Expression prompt = **cute 2D mascot** (same family as the icon glyph: round light face, big glossy eyes,
  pink cheeks, cartoon mouth). A soft-3D "clay" head was tried and dropped (uncanny); PICO's own 3D
  characters are proprietary and cannot be shipped.
