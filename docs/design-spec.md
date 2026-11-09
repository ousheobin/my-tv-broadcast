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
| `gradient_3` | `#0A0C14 → 60% #05050A → #020205` | **Browse flow background** (content area) |
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

All values are design px = dp, authored once at the **1280 × 720dp reference canvas** in
`values/dimens.xml`. Every other bucket is that file scaled, never hand-tuned:

**Screen buckets** — keyed on **available width (`-w`), not smallest width (`-sw`)**. A 1080p or
4K Android TV reports its viewport as **960 × 540dp**, so `smallestWidthDp` is 540 on every TV and
any `-sw600dp`/`-sw720dp`/`-sw960dp` tier is unreachable dead code on the target device — it silently
falls back to the phone values. The generated ladder is:

| Bucket | Available width | Scale of the reference file |
|---|---|---|
| `values/` | phones up to the 1280dp reference canvas, and the 960dp-wide TV | × 1 |
| `values-w1920dp` | 1920dp — low-density boxes | × 1.5 |

The rule is **scale up, never down**. A `values-w960dp` tier at × 0.75 kept every element the same
fraction of the screen width, but it scaled type along with layout: body fell to 13.5sp and Micro to
9.8sp, under the 12sp legibility floor below, and the whole page lost the weight the reference file
was tuned at — `dp` already tracks panel density, so the reference file renders at the calibrated
proportions on a 960dp TV. Layout may shrink to fit, type may not shrink to unreadable, and one
bucket cannot carry two scales without mixing them on the same screen, so the 960dp bucket is retired
and served by the base file. Only canvases **wider** than the reference scale up.
`values-w1920dp` is generated from `values/dimens.xml` and must cover every token — a partial tier
falls back per-value to the base file and mixes two scales inside one screen. Hairlines
(`ui_hairline`, `ui_card_stroke`, `divider_height`) and unitless floats (`card_focus_scale`) are
excluded from the scaling. The single-value columns below are reference-canvas numbers; multiply by
the bucket factor for the tier in effect.

| Element | Spec | Notes |
|---|---|---|
| Top header bar | 1280 × 80, `#0A0D14@70%`, bottom shadow | Full width, no radius |
| Search capsule | 280 × 44, rx12, `#FFF@6%` + `#FFF@12%` 1px border + inner highlight | Right side of header |
| Sidebar rail width | 240 (`sidebar_width`) | Separated from content by a 1px `#FFF@10%` vertical hairline that fades out at both ends (`bg_hairline_v`) — no hard stop at either tip |
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
| Hero preview area | `ui_hero_height` 340, full bleed, **adaptive** | Content edge to screen edge, no side margin; rx16 corners with the picture clipped to match; yields to `ui_hero_lane_reserve` when the viewport cannot fit both the picture and one complete lane |
| Hero bottom scrim | `ui_hero_scrim_height` 140 | Bottom gradient keeps channel name readable |
| Hero top scrim | `ui_hero_scrim_top_height` 120 | Top gradient keeps the live label readable on bright frames |
| Hero bottom fade | `ui_hero_fade_height` 64, ends at `ui_hero_fade` `#040409` | Fades the picture into the page background so the bottom edge leaves no hard line |
| Hero left fade | `ui_hero_fade_start_width` 120, starts at `ui_bg_top` | Picture dissolves out of the sidebar boundary instead of being cut vertically |
| Hero play mark | `ui_hero_play_size` 64 at 70% alpha | Centre mark tells the user the hero is playable |
| Hero focus border | `ui_hero_border_glow` 8 + `ui_hero_border_width` 2, rx16 | Frosted white ring: wide `#FFF@15%` glow plus a `#FFF@40%` hairline highlight, never solid white |
| Hero lane reserve | `ui_hero_lane_reserve` 220 | Height the hero gives up to the flow: one complete lane — row gap + title + card + card margins — so the first screen never shows a half card. Measured from the lane tokens, never guessed |
| Channel row pitch | `ui_row_gap` 24 | Title + horizontal card lane |

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
- A state-varying **background** must be a `drawable` selector whose items use `android:drawable`; a `res/color` selector is only valid for `textColor`/`tint` — as a background it is parsed as a `StateListDrawable` and inflates no drawable before API 30, crashing the whole row on older boxes (`InflateException: Error inflating class <unknown>`)

**Browse Flow (one page — Screens B and D merged)**:

Browsing channels is not a second page. The content area is one vertical flow — source tabs, hero preview, then the 「继续观看」 lane followed by one lane per category. Nothing gets replaced, so there is no page transition to animate, no player torn down and rebuilt on the way back, and no moment where the picture and the list are both half-present.

- Top: source filter row only (no separate header bar); the first tab's text starts at the same vertical line as the sidebar brand wordmark and the lane titles below
- Source filter row is Sony-style text tabs: no fill, no border; inactive `#6B7B8F`, active white with a `ui_tab_indicator_height` white underline; focused = solid white block with `#0A0E1A` text
- Body: **horizontal lanes** — one lane per category, each lane = row title (Body 17–18, white bold) + horizontal scrolling card row; card tokens unchanged (319×140 rx16), row pitch `ui_row_gap`; lane title and lane content share the page content margin (no extra inset)
- Lane alignment: the first card's visible edge must land on the same content line as the lane title, so a card carries **no start margin** (`marginStart=0`) and the whole 20dp inter-card gap is held by `marginEnd` (`ui_card_gap`) — a symmetric `layout_margin` silently pushes every card 10dp off the title line
- **A sidebar category scrolls this flow, it does not navigate**: index 0 (`全部`) returns the flow to the top, any other category scrolls its lane to the top of the list and hands focus to that lane's first card. The mapping is by **category name**, never by `index − 1`, because the lane list is re-grouped every time the source filter changes while the rail keeps one row per category
- Source filter re-groups the lanes; card focus state is unchanged (scale + glow + bare play icon)
- **Nothing may be cut by the bottom edge**: the hero shrinks to `viewport − source row − ui_hero_lane_reserve` (floor 0.55 × `ui_hero_height`), which leaves exactly one complete lane on the first screen — title, card and the card label all visible, no half card. The lane below is what tells the user the page continues; it replaces the old trailing 「全部频道」 entry card, which sat off-screen at the end of a lane nobody could scroll to
- **The hero is the head of the flow, not a fixed header**: the lane list carries a top padding equal to the hero's measured height and the hero translates by the list's scroll offset, so scrolling down carries the picture off the top instead of leaving it parked while lanes slide underneath it. Focus can therefore land on a hero that has scrolled away: any upward move from the first lane, the rail's `RIGHT`, and Back must glide the flow to offset 0 first rather than focus an off-screen picture — a hero that takes focus while invisible reads as a dead screen, because every following key press is spent inside a picture nobody can see
- Focus contract: the page opens with focus on the **hero**, never on the rail — a focus that starts in the sidebar with no route out is the difference between browsing and being stuck. Rail rows carry `nextFocusRight` → hero, hero carries `nextFocusDown` → lane list. The root `decorView` must never be made focusable or handed focus: it eats the first D-pad press and leaves the screen with nothing highlighted
- **The rail's selected category follows the flow**: the anchor is derived from the lane that **holds focus**, falling back to the first visible lane, and reads `全部` while the hero or the 「继续观看」 lane owns it. Scroll is not a reliable trigger — focus can move a whole lane without the outer list moving a pixel — so the lane is reported on focus, on scroll and after every explicit hop. Because a scroll fires once per frame, the adapter must swallow a repeat of the current index instead of rebuilding the column
- **Vertical lane hops are explicit, never geometric**: `UP`/`DOWN` on a card is intercepted **on the card itself** and resolved by the adapter — the target lane is aligned to the **top of the visible flow** (the same resting position the flow starts from, so a lane is never left half under the picture). A lane that is already laid out takes two moves: hand focus to the card at the **same column index**, then pin the lane by the pixel offset measured off that laid-out child — pinning with a *scroll* never re-anchors, which is why it holds. A lane still off-screen takes three, in this order: pull it into the layout with an offset that already cancels the hero padding **plus `ui_row_gap`**, hand over focus, and pin **after** the layout pass that the focus change itself schedules — `LinearLayoutManager` anchors such a pass to the lane that holds focus at its padding box, so a pin applied before it is erased one frame later. The pin offset is never computed from `−heroHeight`, and clamping it to that lane's nearest card is what keeps the column when a lane is shorter. A listener on the row's `RecyclerView` is useless: the focused card's own `onKeyDown` performs the focus move and consumes the key first, and the resulting system search only ever scrolls the next card *just* into view. `DOWN` on the last lane is swallowed (otherwise focus leaks into the sidebar); `UP` on the first lane glides back to the hero, and the first lane's resting position **is** the flow's top, because above it there is only the picture. A lane near the end of the stream rises only as far as the content below it allows — scrolling blank space onto the screen is the wrong trade
- **No focus move may move the flow**: `RecyclerView.requestChildFocus` hands the focused nested view to `LayoutManager.requestChildRectangleOnScreen`, which scrolls the list until that view sits inside its **padding box** — and here the padding box is the hero's slot. So every `RIGHT` on a card dragged the whole flow back down toward the hero (a pinned lane is off that edge by a full hero height), and the next press then landed in a different lane. The lane list therefore runs on a `FlowLinearLayoutManager` that declines both `requestChildRectangleOnScreen` overloads, and `FlowRecyclerView` declines the `View`-level one too. Overriding only the `View` method looks correct and changes nothing — the stack that scrolls goes through the `LayoutManager`. Declining them is necessary but not sufficient: that same padding box also resurfaces as the anchor of a layout pass triggered by focus, which is why the hop rule above pins after it. Vertical position in this flow is decided only by explicit hops
- `RIGHT` on the **last card of a lane** is swallowed by the card: with nothing further right in its own row, the geometric search would otherwise drop focus into the lane below, which reads as the rows having been rewired
- Back: while the flow is scrolled, back returns it to the hero and consumes the key; only from the top does back leave the app. One key press, one visible consequence

**Hero (head of the Browse Flow)**:

- Hero preview is **full bleed**: it runs from the content-area edge to the screen edge with no side margin, height `ui_hero_height` (shrinking per the flow rule above); only the overlaid text keeps the shared content line. Corners are rx16 (`ui_card_radius`) and the picture is clipped to them via `clipToOutline` + `outlineProvider="background"`, so the ring reads as a card edge rather than a cut line
- Video is cropped to fill — `RESIZE_MODE_ZOOM` plus a 1.06 overscan scale on the preview player — so broadcast letterboxing never shows as black bars
- Background `bg_hero_ambient` (gradient_2 deep blue + radial accent glow) shows through before the first frame
- Top scrim `ui_hero_scrim_top_height` (`bg_hero_scrim_top`) starts **fully opaque at `ui_bg_top`** so the picture also fades out of the top edge, and keeps the Micro status readable over bright picture
- Left fade `ui_hero_fade_start_width` (`bg_hero_fade_start`) runs fully opaque `ui_bg_top` → transparent across the first 120dp, so the picture dissolves out of the sidebar boundary rather than ending on a vertical cut
- A separate bottom fade `ui_hero_fade_height` (`bg_hero_fade_bottom`) sits above the readability scrim and ends fully opaque at `ui_hero_fade` — the page background color sampled where the hero meets it — so the picture dissolves into the page instead of stopping on a hard line
- **The hero is focusable and actionable**: it holds the channel currently being previewed, and confirming it enters full-screen playback of that channel. Focus shows a 70%-alpha play mark at the centre (`hero_play_src` selector) plus a frosted white ring (`hero_frost_border`) — the ring is translucent (15% glow + 40% hairline highlight) and rx16, because a solid-white outline would redraw a hard line on the faded edges. No scale: scaling a full-bleed block wobbles the whole page
- The ring must be applied as a child `View` with `duplicateParentState`, not as `android:foreground`: a foreground drawable does not receive `state_focused` here
- `android:tint` cannot dim this mark: a vector's own opaque `fillColor` refills the alpha, so the mark would stay visible when unfocused. Visibility is switched through `android:src` and the opacity through `android:alpha`
- Focus path closes the loop: sidebar `RIGHT` → hero → `DOWN` → lane list → `UP` → hero
- Real video layer = `PlayerView` (TextureView), starts at `alpha=0`; fades to 1 only after the first frame; on error/timeout (6s) it fades back to 0 so the ambient layer shows
- Bottom scrim `ui_hero_scrim_height` (`card_gradient_overlay`); bottom-left channel name (Display white) + current EPG programme (`#8A9BB0`, `ui_row_sub_size`); top-left Micro status (`live_now` / `hero_connecting`)
- Below the hero the flow continues with the 「继续观看」 lane — the default order **with the hero's own channel taken out**, because the picture above already is that channel and one screen must never show it twice; the channel is still reachable inside its category lane. When the pinned head channel changes the list is re-scrolled to position 0, because RecyclerView keeps the old layout anchor and leaves the row offset off the content line
- **The hero is pinned, it is not a follow-focus preview**: it always carries the last played channel, or the head of the flow when nothing has been played yet, and moving focus across cards never changes the picture. What is on screen above must not depend on where the remote happens to be pointing; leaving for playback and coming back re-pins to the channel just watched. Confirming a card opens fullscreen playback, which is the only way to watch anything else

**Cold-Start Landing (gate in front of the Browse Flow)**:

- Covers the flow only while the merged cache is still being parsed. Two elements and no more: the wordmark, and one status line under it naming what is being waited for (`landing_loading_channels`, then `landing_loading_epg`). No spinner, no tagline
- The EPG phase may hold the exit for at most 900ms after the channels land — long enough for the caption to be readable, never long enough for the programme table to gate the UI
- The overlay is `clickable` and **must not be `focusable`**, and its fade-out must leave it `GONE` even when the animation is interrupted: a full-screen layer that holds focus, or stays visible at alpha 0, swallows every remote keypress behind it and looks exactly like a dead app
- Grace period 450ms before it appears: a start that fills content within the window never shows it, so a warm box does not flash a splash
- Reveal: the wordmark strokes draw on in writing order (`trimPathStart` 1→0 over the named paths of `wordmark_mytv`), last stroke landing at 1500ms — the mark builds itself instead of fading in. This is best-effort: on the current TV device class the trim animation resolves to its end state in a single frame, so the mark must already read complete at t=0 and the breathing below is what carries "still working"
- Wait: once drawn, the mark breathes alpha 1 → 0.45 (1200ms per leg, reverse, infinite). Nothing else moves
- Exit: 260ms fade the moment the first content callback arrives (`onCategoriesLoaded`), so the home takes over without a hard cut; dropped with **no** animation whenever the page leaves the foreground, so returning from playback shows the home rather than the landing
- Restoring the last channel skips the grace period — playback launch always costs time, so the landing shows immediately
- Safety net: auto-dismiss at 6s if the content callback never arrives (fragment view reclaimed), so the landing can never outstay its welcome
- The wordmark stays a stroke-only vector with `android:name` on every path so an AVD can target them; the drawer header keeps referencing the same static vector unchanged

**Launcher Icon & TV Banner**:

- Frosted cold-blue-black card (`#0A0E1A → #020308`) with fine grain, a top sheen and a 1px inner edge; a flat neutral black is rejected because it reads as an Apple TV clone
- The stroke wordmark is the entire mark — `y` alone takes `accent` `#AFC6FF`, the other strokes stay 92% white
- Frosted glass is faked with gradients, because real blur needs API 31 while the app ships from API 21
- API 21–25 get baked bitmaps (icon 48/72/96/144/192); API 26+ get an adaptive pair — background gradient + accent glow, foreground wordmark inside the 66dp safe zone
- The banner is 320×180dp full-bleed with no baked rounding (the launcher clips it) and no inner edge, so cropping cannot leave a half-drawn frame; the square icon is never reused as the banner
- Assets come from `scripts/generate_brand_assets.py`; glyph geometry stays identical to `wordmark_mytv`

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
| 12 | Channel lanes (former Screen B) layout | **Changed**: single 3-column grid | **Horizontal lanes per category**, unchanged card tokens — now part of the one-page flow |
| 13 | Browse flow (Screens B + D merged) | **Done**: hero + continue-watching + every category lane on one scrolling surface, no page switch, adaptive hero height | See Section 3 Browse Flow |

---

## 8. Implementation Mapping (Android Resources)

Spec Token → recommended resource naming (keep existing `ui_` prefix, no brand wording):

```
# Tiers: the four-value ladders below are the retired -sw ladder, kept as history only.
# The live rule is Section 3 "Screen buckets": values/dimens.xml is the single authored file
# (1280dp canvas) and also serves the 960dp TV; values-w1920dp is generated from it at x1.5.
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
dimen  ui_hero_height                         → 340 reference; adaptive, floors at 0.55 x
dimen  ui_hero_lane_reserve                   → add 220 (height the hero gives up to one complete lane)
dimen  ui_hero_scrim_height                    → add 140
dimen  ui_row_gap                              → add 24 (lane pitch)
color  ui_hero_ambient_top/mid/bottom         → add gradient_2 stops for hero fallback
color  ui_hero_glow                           → add #140099CC (radial accent)
drawable bg_hero_ambient                      → gradient_2 linear + radial glow layer-list
dimen  ui_card_gap                            → add 20 (lane inter-card gap, held by marginEnd only so the first card stays on the content line)
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
drawable bg_drawer_indicator                  → add selector: active white (`ui_text_primary`), otherwise transparent; never a `res/color` list as a background
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
color  ui_wordmark                            → add #EBFFFFFF (92% white, stroke-only wordmark ink)
drawable wordmark_mytv                        → add stroke-only wordmark vector, `android:name` on every path as animation targets
drawable wordmark_mytv_animated               → add AVD drawing those paths with `trimPathStart` 1→0 in writing order (1500ms to the last stroke)
dimen  ui_landing_wordmark_height             → add 56 / 68 / 78 / 88 (centred landing mark, width follows `adjustViewBounds`)
mipmap ic_launcher                            → full-bleed square-cornered frosted card + wordmark, bitmap densities only (90/135/180/270/360), no adaptive layers
drawable tv_banner                            → single density-less launcher banner (288x162), wired to `android:banner` on **`<application>`** and on the activity
```

### Launcher icon & TV banner sizes

The rules below are taken from a working reference — 芒果TV TV版 (`com.starcor.mango`
7.1.701), whose tile renders correctly on the same class of Chinese TV launcher —
not from the Google TV emulator, which applies a ~24% parallax offset to banners and
therefore cannot be used as a calibration source.

Three rules decide whether a TV launcher actually shows the artwork:

* `android:banner` must sit on the **`<application>`** tag. Third-party launchers read
  `ApplicationInfo.banner` and never resolve an activity — the reference app leaves the
  activity's `icon`/`banner` empty and declares both only on `<application>`.
* **Do not ship `mipmap-anydpi-v26/ic_launcher.xml`.** The `-v26` qualifier wins over
  every density bucket from API 26 up, so on an Android 9 TV the adaptive XML shadows
  the PNGs entirely: the launcher receives an `AdaptiveIconDrawable`, applies its own
  rounded-rect mask and draws it at the icon slot size, which lands as a small dark
  rounded square centred on the launcher's card. The reference APK ships no `mipmap/`
  folder at all.
* The tile shows the **banner, never the icon** — so the banner carries all the visual
  weight. An icon with transparent rounded corners is fine (the reference's 512x512
  icon has alpha 0 at all four corners); an oversized or off-centre banner is not.

| Density | Google TV icon (min) | Shipped icon |
| --- | --- | --- |
| mdpi | 80x80 | 90x90 |
| hdpi | 120x120 | 135x135 |
| xhdpi | 160x160 | **180x180** |
| xxhdpi | 240x240 | 270x270 |
| xxxhdpi | 320x320 | 360x360 |

The icon column is the domestic TV store baseline (180x180 at xhdpi, which Chinese TV
launchers expect in `mipmap-xhdpi`) and clears the Google TV minimums. A further
512x512 icon is exported to `docs/brand/icon-512.png` for store backends; it is
deliberately not packaged into the APK.

The banner is **one density-less file**, `res/drawable/tv_banner.png` at 288x162 — the
same shape as the reference app's `mgtv_app_banner.png` — rather than five density
buckets, because old TV launchers resolve density differently and a single default
asset removes the whole class of fallback behaviour. Oversized banners get cropped
from the top-left instead of scaled, which pushes a centred logo out of the visible
window. The wordmark spans 55% of the width to match the reference's ~58%, and stays
centred inside the safe zone. `android:roundIcon` is deprecated and themed icons are
unsupported on TV, so no adaptive or monochrome layers are provided at all.
