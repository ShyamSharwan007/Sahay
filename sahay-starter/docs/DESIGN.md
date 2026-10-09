# DESIGN.md — Sahay look, feel and UX rules (FROZEN)

Applies to the Android app (A) and the admin dashboard + landing page (D). Read fully before any UI work.

## 1. Personality: "calm under pressure"

The user may be scared, wet, on 8% battery, holding the phone with one hand, reading a language that isn't theirs.
Every screen answers three questions at a glance: **Am I safe? What should I do now? Where do I go?**

Principles
1. **One primary action per screen.** It is the largest, highest-contrast element. Everything else is secondary.
2. **Say it in plain words.** Max 12 words per sentence in the UI. No jargon ("geofence", "mesh", "DBSCAN" never appear to users).
   Say "Phones nearby: 3", not "Mesh peers: 3".
3. **Icon + word, always.** No icon-only buttons, except the standard back arrow and close (with content descriptions).
4. **Status before detail.** A colored status card on top, details below.
5. **Never a dead end.** Every empty, error or offline state says what happened and offers one next action.
6. **Honest labels.** Simulated data is labelled "Simulation". Unverified reports say "Unconfirmed". Candidate shelters say "Possible shelter".
7. **Big targets.** Minimum touch target 48 dp; primary buttons 56 dp tall, full width. Emergency buttons 72 dp.
8. **Works at 200% font scale and in RTL (Arabic).** Use start/end, never left/right.

## 2. Color tokens

Brand: deep teal = calm and trustworthy. Status colors are reserved for meaning; never decorative.

| Token | Light | Dark | Use |
|---|---|---|---|
| background | `#F6F8F7` | `#0D1413` | App background |
| surface | `#FFFFFF` | `#141D1C` | Cards, sheets |
| surfaceVariant | `#EBF0EE` | `#1C2726` | Chips, input fields, secondary cards |
| outline | `#C5D0CD` | `#2F3D3B` | Dividers, borders |
| onBackground / onSurface | `#132120` | `#E5EEEC` | Main text |
| onSurfaceVariant | `#4A5A57` | `#A3B3B0` | Secondary text |
| primary | `#0B6E6E` | `#5FD3CA` | Primary buttons, links, selected states |
| onPrimary | `#FFFFFF` | `#00332F` | Text on primary |
| primaryContainer | `#CDEDE9` | `#0F4A47` | Highlighted cards |
| onPrimaryContainer | `#00302D` | `#BDF1EB` | |
| safe | `#18794C` | `#4ADE95` | "You're safe", open shelters, groups at shelter |
| watch | `#9A6700` | `#F2C14E` | Severity 1 |
| warning | `#C2410C` | `#FF9F5A` | Severity 2, likely reports |
| danger | `#C62828` | `#FF6B6B` | Severity 3, SOS, risk zones, full shelters |
| info | `#2457C5` | `#86ABFF` | Severity 0, tips |
| on-status text | `#FFFFFF` | `#0D1413` | Text on filled status colors |

Container variants of each status (backgrounds behind status text): light = status color at 12% opacity over surface; dark = 18%.

**Emergency theme** (used whenever Emergency Mode is on, regardless of light/dark): background `#000000`, surface `#0B0B0B`,
text `#FFFFFF`, secondary text `#BDBDBD`, danger `#FF453A`, safe `#30D158`, primary `#64D2FF`. No gradients, no animation, no blur (OLED + battery).

Theme modes: System (default), Light, Dark — switch in Settings. Persisted via `UiPreferences`. The admin dashboard has the same three modes.

Map styles: light map in light theme, dark map in dark and emergency themes. Overlay colors: risk zone HIGH = danger at 28% fill + 2 px danger outline;
MEDIUM = warning at 20% fill; route = primary 6 px with a 2 px white (light) / black (dark) casing; my location = info dot with halo.

## 3. Typography

Font: **Manrope** (OFL), bundled in the app (`res/font`, variable or 400/500/600/700/800) — never downloadable fonts (offline).
Covers Latin and Cyrillic; Japanese, Korean, Chinese, Arabic and Tamil fall back to the system Noto fonts automatically.
Admin web: Manrope from Google Fonts.

| Style | Size / line height | Weight | Use |
|---|---|---|---|
| display | 34 / 40 sp | 800 | Emergency screen headline, SOS countdown |
| headline | 26 / 32 | 700 | Screen titles |
| title | 20 / 26 | 700 | Card titles |
| bodyLarge | 17 / 26 | 500 | Alert body text, instructions |
| body | 15 / 22 | 500 | Default text |
| label | 14 / 18 | 600 | Buttons, chips |
| caption | 12 / 16 | 600 | Timestamps, meta (letter-spacing +0.2) |

Minimum body size 15 sp. Alert body in the user's language is always bodyLarge; the English line below it is body in onSurfaceVariant.

## 4. Shape, spacing, elevation, motion

- Spacing scale: 4, 8, 12, 16, 20, 24, 32, 40 dp. Screen side padding 20 dp. Gap between cards 12 dp.
- Corner radius: cards 20 dp, buttons 16 dp, chips 12 dp, sheets 28 dp (top), status dots full.
- Elevation: mostly flat. Cards use a 1 dp outline in light mode and surface color steps in dark mode. Only the SOS button and bottom sheets cast shadows.
- Motion: 150–250 ms, standard easing. Allowed: fade/slide between screens, progress, a soft pulse on the SOS button (disabled in emergency theme and when "Remove animations" is on).
- Icons: Material Symbols Rounded (outlined, weight 500). One icon set only.

## 5. Components (A builds in `core/designsystem`; D mirrors them in the admin with Tailwind)

| Component | Spec |
|---|---|
| `SahayButton` | Variants: Primary (filled primary), Secondary (tonal surfaceVariant), Danger (filled danger), Ghost (text). Sizes: M 48 dp, L 56 dp, XL 72 dp. Leading icon + label. Loading state with spinner, disabled state. |
| `StatusCard` | Full-width card with a 6 dp start border in a status color, icon in a tinted circle, title + one-line body, optional action. States: Safe, Watch, Warning, Danger, Info, Offline. |
| `SeverityBadge` | Pill with icon + word ("Emergency", "Warning", "Watch", "Info"), status color container. |
| `VerificationBadge` | "Verified official" (safe, shield-check icon), "Looks official — not verified" (watch, shield icon), "Unverified" (outline). |
| `TrustChip` | "Verified" / "Likely" / "Unconfirmed" + small score bar (0–1). |
| `ConnectivityChip` | Top-bar pill: "Online" (safe dot) / "SMS only" (watch dot) / "Offline · 3 phones nearby" (outline). Tap opens a sheet explaining what still works. |
| `BigActionTile` | 2-column grid tile, 112 dp tall: icon in tinted circle, label 2 lines max. Used on Home and Emergency screens. |
| `OfflineBanner` | Thin banner under the top bar: "Offline — using saved data from 10:42". |
| `EmptyState` / `ErrorState` / `LoadingState` | Icon (64 dp), title, one-sentence body, one action button. Skeleton shimmer for loading lists (no shimmer in emergency theme). |
| `StepProgress` | Vertical checklist with done/active/pending icons — used for pack download and onboarding. |
| `SectionHeader` | caption-style overline + optional "See all". |
| `SahayTopBar` | Title start-aligned, ConnectivityChip at end. |
| `SahayBottomBar` | 4 destinations: Home, Map, Alerts (with unread badge), Me. Hidden in Emergency Mode. |
| `SosButton` | Circular 88 dp danger button with "SOS" label, used on Home and Emergency screen; long-press not required, but a 5-second cancellable countdown follows. |

## 6. Screen map (Android)

```
Language → Welcome (3 slides) → Sign in (Google / Continue as guest)
→ Profile setup: 1 Essentials · 2 Medical · 3 Emergency contacts · 4 Where you stay · 5 Privacy choices · 6 Permissions
→ Trip setup: Region → Dates → Download (StepProgress) → Ready (precautions)
→ Main: Home · Map · Alerts · Me
   Home → Go to safety (Navigate) · SOS · Show to a local · Report hazard · Find people · Precautions · Emergency Mode
   Map → filters, POI sheet, report sheet, group sheet → Navigate
   Alerts → Alert detail (Listen, Go to safety, Show original) · Paste an alert
   Me → Edit profile · Medical card · Trip pack · Theme · Language · Privacy · About & licenses · Sign out
Emergency Mode (full-screen, black): Go to safety · SOS · Show to a local · Find people · battery estimate · connection · Exit (hold 2 s)
```

Key screen notes
- **Home:** top StatusCard answers "Am I safe?" from RiskMonitor + latest unread alert (Danger if severity 3 alert covers the user).
  Below: today's weather line, 2×3 BigActionTile grid, then precautions. If no pack: a single card "Download your trip pack" with one button.
- **Navigate:** map with route, big distance + ETA ("650 m · 9 min walk"), destination name in user language + Tamil, badge "Avoids flood-prone roads" or warning.
  Bearing arrow toward next route point when off-screen. Button "Show this to a local" (opens card with destination in Tamil).
- **Alert detail:** SeverityBadge + VerificationBadge, title (headline), body (bodyLarge) in user language, English below, "Show original" expander,
  primary button "Go to safety" for severity ≥ 2, "Listen" button (TextToSpeech in user language, falls back to English).
- **SOS:** big countdown 5→0 with "Cancel" (XL secondary). Then result list per contact (sent / failed) and "Call 112" XL danger button.
  Shows exactly what was sent. Works with no internet.
- **Show to a local:** landscape-friendly, white background even in dark theme (readable in sun), max brightness while open.
  Tamil headline in display size, user's language below in body, hotel address, destination, medical summary (blood group, allergies).
  Tab 2: Phrasebook (categories as chips, each phrase: Tamil large + own language small + icon; tap = speak Tamil via TTS if available).
- **Report hazard:** 2×4 grid of hazard types (icon + word), location row ("Your location" / "Pick on map"), optional note and photo, Submit.
  After submit: "Saved. Will send when connected" or "Sent", with TrustChip once known.
- **Find people:** privacy line at top ("Only groups of 5+ are shown. Nobody sees you personally."), toggle "I'm alone — let nearby groups know",
  list of groups with status chip and distance ("7 people · at a shelter · 400 m"), "Go" button per group (only for AT_SHELTER and SAFE_AREA),
  "Phones nearby: 3" line when Bluetooth mesh is on. RISK_ZONE groups show "Don't go — flood risk" with no Go button.
- **Emergency Mode:** black background, display headline ("Stay calm. Here's what to do."), 4 XL tiles, battery % and estimate ("~9 h left"),
  ConnectivityChip, "Exit Emergency Mode" requires press-and-hold 2 s.

## 7. Writing style (all 9 languages)

- Use "you". Active voice. Verbs first on buttons: "Go to safety", "Send SOS", "Show to a local", "Report a hazard", "Find people".
- Numbers with units: "650 m", "9 min walk", "Updated 4 min ago".
- Never blame: "Couldn't get your location. Move near a window and try again." not "GPS error".
- Translations: A generates `values-<lang>/strings.xml` for de, fr, es, ru, ja, ko, zh, ar from English, keeping placeholders; native speakers on the team spot-check.

## 8. Accessibility checklist

- Text contrast ≥ 4.5:1 (tokens above pass on their backgrounds).
- Every icon button and image has a content description; decorative icons have none.
- Works with TalkBack: logical focus order, headings marked, status changes announced (liveRegion on StatusCard and SOS result).
- Supports system font scale up to 200% without clipped text (no fixed heights on text containers).
- Supports RTL (Arabic) mirroring.
- Never rely on color alone: every status has an icon and a word.

## 9. Admin dashboard and landing page (D)

Stack: Vite + React + TypeScript + Tailwind + shadcn/ui-style components, MapLibre GL JS (OpenFreeMap style online), lucide icons.
Layout: left sidebar (Overview, Publish alert, Shelters, Reports, People & groups, SMS log), top bar with region switcher,
theme toggle (System/Light/Dark) and a persistent amber "SIMULATION MODE" badge when the simulation toggle is on.
Same color tokens as CSS variables on `:root` and `[data-theme="dark"]`. Cards radius 16 px, 1 px outline, generous whitespace.
Landing page (`/`): hero with app name, one-line pitch, "Download APK" primary button, "Watch demo" secondary, 3 feature cards,
"How judges can test" steps, link to the GitHub repo. Must look good on phones (judges may open it on a phone to download the APK).
