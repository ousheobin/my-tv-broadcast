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
| Sidebar rail width | 240 / 280 / 310 / 340 (`sidebar_width`) | Separated from content by a 1px `#FFF@10%` vertical hairline that fades out at both ends (`bg_hairline_v`) — no hard stop at either tip |
| Sidebar nav row | Full width × 56, text edge at 48, row spacing 8 | No fill, no border; focus block inset 12 on both sides, rx12 |
| Sidebar active indicator | 4 × 22 at x=32 (`ui_drawer_indicator_*`) | White bar on the active row only |
| Sidebar brand title | 36sp bold, letter-spacing 0.06, height 88, same 48 edge | `MyTV` |
| Sidebar settings entry | Same row metrics; 26dp icon + 14 gap, hairline above | Shares `bg_drawer_item` + `drawer_item_text` |
| Content row (settings page) | 940 × 72, rx16; 932 × 64, rx12 | |
| **Channel card** | **319 × 140, rx16** (horizontal rectangle) | 3 columns × N rows |
| Card horizontal gap | ≈ 20 | |
| Card vertical gap | ≈ 24 | |
| Logo square placeholder | 160 × 160, rx12 | |
| Bottom control bar | 1280 × 140, `#05080D@85%`, top shadow | Full width |
| Progress bar | Track 400 × 4 rx2 `#262626`; fill `#FFF` rx2 | |
| Page horizontal safe margin | Content starts at x≈24, ends at 1256 | ≈ 24dp |
| Hero preview area (Screen D) | `ui_hero_height` 340 (base) / 442 / 510 / 578, full bleed | Content edge to screen edge, no side margin; rx16 corners with the picture clipped to match |
| Hero bottom scrim | `ui_hero_scrim_height` 140 / 182 / 210 / 238 | Bottom gradient keeps channel name readable |
| Hero top scrim | `ui_hero_scrim_top_height` 120 / 156 / 180 / 204 | Top gradient keeps the live label readable on bright frames |
| Hero bottom fade | `ui_hero_fade_height` 64 / 83 / 96 / 115, ends at `ui_hero_fade` `#040409` | Fades the picture into the page background so the bottom edge leaves no hard line |
| Hero left fade | `ui_hero_fade_start_width` 120 / 156 / 180 / 204, starts at `ui_bg_top` | Picture dissolves out of the sidebar boundary instead of being cut vertically |
| Hero play mark | `ui_hero_play_size` 64 / 83 / 96 / 115 at 70% alpha | Centre mark tells the user the hero is playable |
| Hero focus border | `ui_hero_border_glow` 8 / 10 / 12 / 14 + `ui_hero_border_width` 2 / 3 / 3 / 4, rx16 | Frosted white ring: wide `#FFF@15%` glow plus a `#FFF@40%` hairline highlight, never solid white |
| Channel row pitch (Screen B/D) | `ui_row_gap` 24 / 31 / 36 / 40 | Title + horizontal card lane |

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

**Sidebar Rail (Screens B/D) Structure**:

- No capsules, no borders: every row is plain text on the transparent page background — inactive `#6B7B8F`, active white with a 4dp white bar at x=32, focused = solid white block (inset 12 left/right, rx12) with `#0A0E1A` text and the bar hidden
- No scale animation on focus: a full-width row must not jitter, the inverted block alone carries the focus signal
- One alignment system: brand title, nav text and settings icon all start at `ui_drawer_indent` (48); the focus block starts at `ui_drawer_block_inset` (12)
- The block inset must be a `layer-list` item offset, never an `<inset>` drawable: `InsetDrawable` reports padding to the View, which silently pushes every row's content 12dp right and breaks the 48dp line

**Screen B (Channel Page) Structure**:

- Top: source filter row only (no separate header bar); the first tab's text starts at the same vertical line as the sidebar MyTV title and the lane titles below
- Source filter row is Sony-style text tabs: no fill, no border; inactive `#6B7B8F`, active white with a `ui_tab_indicator_height` white underline; focused = solid white block with `#0A0E1A` text
- Body: **Sony-style horizontal lanes** — one lane per category, each lane = row title (Body 17–18, white bold) + horizontal scrolling `ChannelAdapter` row; card tokens unchanged (319×140 rx16), row pitch `ui_row_gap`; lane title and lane content share the page content margin (no extra inset)
- Category selection in the sidebar scrolls the lane list to the matching row (index 0 = top)
- Source filter re-groups the lanes; card focus state is unchanged (scale + glow + bare play icon)

**Screen D (Home / Preview) Structure**:

- Hero preview is **full bleed**: it runs from the content-area edge to the screen edge with no side margin, height `ui_hero_height`; only the overlaid text keeps the shared 48dp content line. Corners are rx16 (`ui_card_radius`) and the picture is clipped to them via `clipToOutline` + `outlineProvider="background"`, so the ring reads as a card edge rather than a cut line
- Video is cropped to fill — `RESIZE_MODE_ZOOM` plus a 1.06 overscan scale on the preview player — so broadcast letterboxing never shows as black bars
- Background `bg_hero_ambient` (gradient_2 deep blue + radial accent glow) shows through before the first frame
- Top scrim `ui_hero_scrim_top_height` (`bg_hero_scrim_top`) starts **fully opaque at `ui_bg_top`** so the picture also fades out of the top edge, and keeps the Micro status readable over bright picture
- Left fade `ui_hero_fade_start_width` (`bg_hero_fade_start`) runs fully opaque `ui_bg_top` → transparent across the first 120dp, so the picture dissolves out of the sidebar boundary rather than ending on a vertical cut
- A separate bottom fade `ui_hero_fade_height` (`bg_hero_fade_bottom`) sits above the readability scrim and ends fully opaque at `ui_hero_fade` — the page background color sampled where the hero meets it — so the picture dissolves into the page instead of stopping on a hard line
- **The hero is focusable and actionable**: it holds the channel currently being previewed, and confirming it enters full-screen playback of that channel. Focus shows a 70%-alpha play mark at the centre (`hero_play_src` selector) plus a frosted white ring (`hero_frost_border`) — the ring is translucent (15% glow + 40% hairline highlight) and rx16, because a solid-white outline would redraw a hard line on the faded edges. No scale: scaling a full-bleed block wobbles the whole page
- The ring must be applied as a child `View` with `duplicateParentState`, not as `android:foreground`: a foreground drawable does not receive `state_focused` here
- `android:tint` cannot dim this mark: a vector's own opaque `fillColor` refills the alpha, so the mark would stay visible when unfocused. Visibility is switched through `android:src` and the opacity through `android:alpha`
- Focus path closes the loop: sidebar `RIGHT` → hero → `DOWN` → continue-watching lane → `UP` → hero
- Real video layer = `PlayerView` (TextureView), starts at `alpha=0`; fades to 1 only after the first frame; on error/timeout (6s) it fades back to 0 so the ambient layer shows
- Bottom scrim `ui_hero_scrim_height` (`card_gradient_overlay`); bottom-left channel name (Display white) + current EPG programme (`#8A9BB0`, `ui_row_sub_size`); top-left Micro status (`live_now` / `hero_connecting`)
- Below hero: one 「继续观看」 lane (last played channel first, then default order) + a trailing 「全部频道」 entry card (same card size/focus state, `ic_all_channels` icon)
- Focusing a channel card debounces ~600ms then switches the hero preview to that channel (muted); clicking opens fullscreen playback; the trailing entry opens Screen B

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
| Source filter tab (Screen B) | Transparent, text `#6B7B8F`; active tab = white text + bottom white underline | **Solid `#FFF` block, `#0A0E1A` text** (underline hidden) |
| Sidebar nav row (Screens B/D) | Transparent, text `#6B7B8F`; active = white text + 4dp white bar | **Solid `#FFF` block inset 12, `#0A0E1A` text** (bar hidden), no scale |
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
| 12 | Channel page (Screen B) layout | **Changed**: single 3-column grid | **Sony-style horizontal lanes per category**, unchanged card tokens |
| 13 | Home page (Screen D) | **New**: hero live preview + continue-watching lane + all-channels entry | See Section 3 Screen D structure |

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
dimen  ui_hero_height                         → add 340 / 442 / 510 / 578 (Screen D hero)
dimen  ui_hero_scrim_height                   → add 140 / 182 / 210 / 238
dimen  ui_row_gap                             → add 24 / 31 / 36 / 40 (lane pitch)
color  ui_hero_ambient_top/mid/bottom         → add gradient_2 stops for hero fallback
color  ui_hero_glow                           → add #140099CC (radial accent)
drawable bg_hero_ambient                      → gradient_2 linear + radial glow layer-list
drawable ic_all_channels                      → trailing “all channels” entry glyph
dimen  ui_tab_indicator_height                → add 3 / 4 / 4 / 5 (source tab underline)
dimen  ui_source_tab_padding_h                → add 12 / 16 / 16 / 20 (source tab horizontal padding)
dimen  ui_source_tab_gap                      → add 8 / 10 / 12 / 14 (source tab spacing)
dimen  ui_source_tab_row_start                → add 36 / 32 / 32 / 28 (keeps tab text on the lane title line)
color  source_tab_text                        → add selector: focused `#0A0E1A` / active `#FFF` / idle `#6B7B8F`
drawable bg_source_tab                        → add selector: transparent / white underline (active) / solid white (focused)
dimen  sidebar_width                          → add tier overrides 240 / 280 / 310 / 340
dimen  ui_drawer_item_height                  → 56 / 66 / 74 / 84 (row height)
dimen  ui_drawer_indent                       → add 48 / 56 / 64 / 72 (text edge shared with brand title)
dimen  ui_drawer_block_inset                  → add 12 / 16 / 18 / 20 (focus block side inset)
dimen  ui_drawer_indicator_width              → add 4 / 5 / 5 / 6
dimen  ui_drawer_indicator_height             → add 22 / 27 / 31 / 36
dimen  ui_drawer_indicator_margin             → add 32 / 38 / 44 / 50
dimen  ui_drawer_header_height                → add 88 / 104 / 118 / 134
dimen  ui_drawer_brand_size                   → add 36sp / 44sp / 50sp / 57sp
dimen  ui_drawer_icon_size                    → add 26 / 32 / 36 / 41
dimen  ui_drawer_icon_gap                     → add 14 / 18 / 21 / 24
color  drawer_item_text                       → add selector: focused `#0A0E1A` / active `#FFF` / idle `#6B7B8F`
color  drawer_indicator                       → add selector: active white, otherwise transparent
drawable bg_drawer_item                       → add selector: transparent / solid white layer-list offset (focused)
dimen  ui_hero_scrim_top_height               → add 120 / 156 / 180 / 204 (hero top scrim)
drawable bg_hero_scrim_top                    → add top-down dark gradient keeping the live label readable
color  ui_hero_fade                           → add #040409 (page background sampled at the hero bottom edge)
dimen  ui_hero_fade_height                    → add 64 / 83 / 96 / 115 (hero bottom fade band)
drawable bg_hero_fade_bottom                  → add transparent → `ui_hero_fade` gradient dissolving the picture into the page
dimen  ui_hero_fade_start_width               → add 120 / 156 / 180 / 204 (hero left fade band)
drawable bg_hero_fade_start                   → add `ui_bg_top` → transparent, horizontal
drawable bg_hairline_v / bg_hairline_h        → add transparent → `ui_border` → transparent, so hairlines fade out at both ends
dimen  ui_hero_play_size                      → add 64 / 83 / 96 / 115 (hero focus play mark)
drawable hero_play_src                        → add selector: focused `ic_play_circle`, else transparent
color  ui_frost_glow / ui_frost_edge          → add #26FFFFFF (15%) and #66FFFFFF (40%) for the frosted ring
drawable hero_frost_border                    → add selector: focused = rx16 glow stroke + inset hairline highlight
view   hero_container                         → rx16 corners, `clipToOutline` + `outlineProvider="background"` so the picture follows the ring
```
