# My TV Broadcast · Design Token Spec

## 1. Color Tokens (Primitive)

All fill colors in order of usage frequency:

| Hex | Alpha | Frequency | Usage |
|---|---|---|---|
| `#FFFFFF` | 100% | 72 | Primary text, focused inverted background, progress bar fill |
| `#FFFFFF` | 10% | 13 | 1px glass border inside cards/controls (inner border) |
| `#6B7B8F` | 100% | 12 | Secondary text (blue-gray, card subtitle/meta) |
| `#14171F` | 70% | 11 | **Channel card / settings row background** |
| `#1A1A1A` | 100% | 7 | Dark container (thumbnail placeholder etc.) |
| `#8A9BB0` | 100% | 6 | Tertiary text (lighter blue-gray, tags) |
| `#0A0E1A` | 100% | 6 | **Focus ink text** (inverted text on white capsule) |
| `#0A0D14` | 70% | 2 | Top header bar background |
| `#FFFFFF` | 60% | 5 | Medium emphasis (play circle button stroke etc.) |
| `#FFFFFF` | 50% | 5 | De-emphasized icons / separators |
| `#FFFFFF` | 40% | 2 | Timestamps and weak text |
| `#05080D` | 85% | 1 | Bottom control bar background |
| `#FFFFFF` | 6% | 1 | Search capsule background |
| `#FFFFFF` | 12% | 1 | Search capsule 1px border |
| `#262626` | 100% | 1 | Progress bar track |

### Gradient Tokens (all vertical 180° linear gradients)

| Token | Stops | Application |
|---|---|---|
| `gradient_0` | `#05070F → #020207` | Screen A base background |
| `gradient_1` | `#0A0F1E → 50% #05050C → #020205` | Screen A overlay layer |
| `gradient_2` | `#0F142D → 50% #070A19 → #020207` | Screen A ambient layer |
| `gradient_3` | `#0A0C14 → 60% #05050A → #020205` | **Screen B (channel page) background** |
| `gradient_4` | Radial `#0099CC @7.8% → transparent` | Ambient accent glow |
| `gradient_5` | `#0A0C14 → 60% #05050A → #020205` | **Screen C (settings page) background**, identical stops to gradient_3 |

> ⚠️ Channel page and settings page backgrounds both use **gradient_3/5** (deep ink-blue, more neutral); gradient_1 is only for the Screen A playback overlay.

---

## 2. Corner Radius Tokens

Three radius levels only; no full pills (radius = height/2):

| Value (dp) | Usage |
|---|---|
| `2` | Progress bar |
| `12` | Capsules/buttons/rows (all interactive surfaces with height 44–72) |
| `16` | Channel cards (height 140), large containers (height 140–280) |

---

## 3. Size Tokens (Component Level)

All values are design px = dp:

| Element | Spec | Notes |
|---|---|---|
| Top header bar | 1280 × 80, `#0A0D14@70%`, bottom shadow | Full width, no radius |
| Search capsule | 280 × 44, rx12, `#FFF@6%` + `#FFF@12%` 1px border + inner highlight | Right side of header |
| Sidebar category capsule | 180 × 52 (alt size 220 × 52), rx12 | Vertical list, 8 spacing |
| Content row (settings page) | 940 × 72, rx16; 932 × 64, rx12 | |
| **Channel card** | **319 × 140, rx16** (horizontal rectangle) | 3 columns × N rows |
| Card horizontal gap | ≈ 20 | |
| Card vertical gap | ≈ 24 | |
| Logo square placeholder | 160 × 160, rx12 | |
| Bottom control bar | 1280 × 140, `#05080D@85%`, top shadow | Full width |
| Progress bar | Track 400 × 4 rx2 `#262626`; fill `#FFF` rx2 | |
| Page horizontal safe margin | Content starts at x≈24, ends at 1256 | ≈ 24dp |

**Screen C (Settings Page) Structure**:

- Top 1280×80 `#0A0D14@70%` header, followed by a 1px `#FFF@10%` divider (no shadow, different from Screen B's dy2/blur6)
- Left category nav: 220×52 rx12 capsules; focused state = solid white + `#0A0E1A` ink text
- Right content rows: 940×72 rx16, background `#14171F@70%`, row pitch 88 (gap 16); title white 17–18, body `#8A9BB0` 15
- Bottom large panel: 940×280 rx16 same background, containing a 160×160 white QR code area (module 12×12, `#1A1A1A`)

**Screen A (Playback / Now Playing) Structure**:

- Background: gradient_0/1/2 stacked (deep blue ambient, fallback behind video); implementation simplified to a single vertical gradient `#0F142D → 50% #070A19 → #020207`
- Top: no header bar, only gradient scrims (opacity 0.4/0.6); **top-left shows two Micro-size labels (≈13sp white 50%): quality/status + bitrate**
- Bottom control bar: 1280×140 `#05080D@85%`, top shadow `shadow.panel.top` (dy-4/blur12/α50%, simulated with a 12dp gradient strip); **channel name on the left of the bar**: Display-size white text (glyph h24 ≈34sp) + `#8A9BB0` 15 subtext below (index/info); bottom-right also has small white text + `#8A9BB0` subtext (status info, not yet implemented due to missing data source)
- Progress bar: track 400×4 rx2 `#262626`, fill `#FFF` rx2, Micro timestamps on both sides; **live streams have no duration → decided to omit for now** (tokens/drawables kept for future use)
- Center button: 88dp circle (r44), `#FFF@10%` fill + white ring — spec-only, not used by the loading state
- Loading state: understated center indicator = 44dp ring (`#FFF@5%` fill + `#FFF@20%` 1px stroke, `bg_loading_ring`) holding a 22dp white spinner, with the load percentage caption directly below (`#8A9BB0`, 15sp, tokens `ui_loading_ring_size` / `ui_loading_spinner_size` / `ui_loading_text_gap`); percent tracks buffered-fill toward the playback gate and hits 100% at the first frame

**Card grid validation**: 24 + 319×3 + 20×2 + margins ≈ 1280 ✓

---

## 4. Shadow / Elevation Tokens

| Token | dy | blur | alpha | Usage |
|---|---|---|---|---|
| `shadow.card.deep` | 8 | 16 | 40% black | Channel card elevation |
| `shadow.panel.top` | -4 | 12 | 50% black | Bottom control bar (upward shadow) |
| `shadow.bar` | 2 | 6 | 30% black | Top header bar / regular rows |
| `shadow.icon` | 4 | 8 | 30% black | Small icon elements |
| `glow.white` | 0 | 8 | 15% white | Focus glow |
| `inner.highlight` | inset 1 | 1 | 8% white | Search capsule inner top highlight |
| `inner.shadow` | inset -1 | 1 | 20% black | Control inner bottom shadow |

**Glass border rule**: Card/control borders are a 1px white inner border (`#FFF@10%`), not an outer stroke. On Android, simulate with a `layer-list` or Foreground.

---

## 5. Focus State Spec

| Component | Default | Focused |
|---|---|---|
| Category/filter capsule | `#14171F@70%` + 1px `#FFF@10%` inner border, text `#FFF`/`#6B7B8F` | **Solid `#FFF` inverted + text `#0A0E1A`**, with `glow.white` |
| Channel card | `#14171F@70%` + inner border + `shadow.card.deep` | Scale up (suggested 1.05) + `glow.white` + border brightened to `#FFF@25%` |
| Search capsule | `#FFF@6%` | Border brightened, no inversion |

---

## 6. Text Tokens

Font family follows system Roboto / Source Han Sans.

| Level | Size (sp) | Color | Usage |
|---|---|---|---|
| Display | 34–36 | `#FFF` | Page title ("Program Guide") |
| Title | 24 | `#FFF` | Dialog/section headline |
| Body | 17–18 | `#FFF` | Card titles, capsule text (bold) |
| Subbody | 15 | `#0A0E1A` / `#FFF@60%` | Focus inverted text, secondary emphasis |
| Caption | 12–13 | `#6B7B8F` | Card subtitles, meta info |
| Micro | 10–11 | `#FFF@40–50%` | Timestamps, hints |

Android TV minimum readable size is 12sp; Caption and below should be raised to ≥12sp for 10-foot viewing distance.

---

## 7. Gap List vs. Current Implementation (To Fix)

| # | Item | Current | Spec Requirement |
|---|---|---|---|
| 1 | Page background | gradient_1 (`#0A0F1E→#05050C→#020205`, 315° diagonal) | **gradient_3 (`#0A0C14→60%#05050A→#020205`, vertical 180°)** |
| 2 | Card background | white 6% (`ui_surface_card`) | **`#14171F` @ 70%** |
| 3 | Card ratio | 320dp × 200dp, near 16:10 | **319dp × 140dp horizontal (≈2.28:1)** |
| 4 | Capsule radius | `ui_pill_radius` 28dp full pill | **12dp rounded rectangle** |
| 5 | Border style | outer stroke 1.5dp | **1px inner border `#FFF@10%`** |
| 6 | Card shadow | none / elevation approximation | **y+8 blur16 α40%** |
| 7 | Secondary text | single `#8A9BB0` | Distinguish `#6B7B8F` (subtext) vs `#8A9BB0` (tags) |
| 8 | Focus ink | `#0A0E1A` ✓ | Consistent, no change |
| 9 | Bottom/top bars | header implemented (80dp `#0A0D14@70%` + bottom shadow gradient layer + 34sp title; search capsule omitted per product decision) | Bottom 140dp control bar `#05080D@85%` still not implemented |
| 10 | Settings page (Screen C) | **Aligned**: gradient background, 80dp header + 1px divider, 220×52 rx12 capsule nav, 940×72 rx16 row cards, QR large panel (see Section 3 structure) | — |
| 11 | Playback page (Screen A) | **Aligned**: gradient background, top-left Micro labels (status + bitrate), channel name on left of 140dp bottom bar (Display white + `#8A9BB0` subtext), top shadow, understated 44dp loading ring with percent below; progress bar omitted per product decision | Implemented |

---

## 8. Implementation Mapping (Android Resources)

Spec Token → recommended resource naming (keep existing `ui_` prefix, no brand wording):

```
color  ui_bg_top / ui_bg_mid / ui_bg_bottom   → update to gradient_3 three colors
color  ui_surface_card                        → update to #B314171F (70% #14171F)
color  ui_card_base                           → add #14171F
color  ui_text_caption                        → add #6B7B8F
color  ui_text_secondary                      → keep #8A9BB0 (tag usage)
color  ui_focus_ink                           → keep #0A0E1A
dimen  ui_pill_radius                         → 28dp change to 12dp
dimen  ui_card_radius                         → keep 16dp
dimen  channel_card_height                    → 200dp change to 140dp
dimen  channel_card_width                     → keep 320dp (corresponds to 319)
```
