# Bastion — Protection Model v2

**A concept spec for the next version of the Guard.**
Written to be handed to Claude Code as the brief for a multi-step refactor.

---

## 0. What this is, and the one test it has to pass

Today Bastion answers *"is this app blocked?"*.
It cannot answer *"am I protected?"* — and that is the question a man actually has.

This document replaces the way protection is **modelled, chosen and enforced**. It keeps
every mechanism that already works — the accessibility guard, the DNS filter, SafeSearch,
the title filter, grayscale, lockdown, the cooling-off lock — and changes what sits on top
of them.

**The acceptance test for the whole design:** a man who has never opened a settings screen
should, after ninety seconds of onboarding, be able to read one sentence that is *true* about
what is protected, what is not, and what would defeat it. And when something closes, he
should be able to tap it and be told exactly why.

Nothing here requires a new permission. Nothing here sends anything off the phone.

---

## 1. What is actually wrong with v1

Eight defects. Each is structural — none is a bug that can be fixed where it appears.

**D1 — `BlockMode` is exclusive, but the real dimensions are orthogonal.**
`BlockMode { FULL, SCHEDULE, FEED_ONLY, TIME_LIMIT }` (`data/db/Entities.kt:243`) forces one
choice per app. "Instagram: Reels always closed, whole app after 22:00, 15 minutes a day"
is three true statements a man wants and the schema can hold exactly one of. `ProtectionSection`
is a beautifully written UI (`feature/guardui/ProtectionSection.kt`) built to hide the fact
that the model underneath it is too small.

**D2 — `packageName` is the primary key of a rule, but rules already escaped packages.**
`FeedRuleEntity.packageName` was the owner; then URL rules were made universal
(`rulesFor` / `checkUniversalFeed` in `BastionAccessibilityService.kt:927,960`), so
`com.zhiliaoapp.musically` is now a *label for TikTok-the-service* rather than a package
name. The comment above `builtInFeedRules()` documents eleven generations of this fight.
The schema is now lying, and the eleventh generation will lose to the twelfth window nobody
listed.

**D3 — Three unrelated blocking mechanisms with no shared vocabulary.**
The DNS filter blocks *domains* (`DomainFilter`), feed rules block *screens*
(`FeedSurface`), the title filter blocks *words* (`TitleFilter`). Three UIs, three mental
models, and no single place that can answer "is porn blocked?" — because porn is not a
concept anywhere in the code. Only domains, view ids and words are.

**D4 — Configuration is enumeration, and enumeration always loses.**
Every protection starts from a list someone typed: `SUGGESTED_PACKAGES`, `REAL_BROWSERS`,
`INSTALLER_PACKAGES`, `LAUNCHER_PACKAGES`, the blocklist. Install a thirteenth dating app,
a second browser, a new short-form service, and Bastion silently does nothing. The user is
never told what is *not* covered.

**D5 — "Guard strength" counts permissions, not protection.**
`GuardStrength.kt` reports "5 of 7 on". Those seven are *capabilities* — grants on system
screens. A phone can read 7/7 with zero apps guarded, and 4/7 with everything a man cares
about genuinely closed. The meter measures the wiring, not the house.

**D6 — Blocking is binary, so it is both too soft and too harsh.**
`blockApp()` sends you home and shows a shield. Accessibility blocking is *reactive by
nature* — the screen was already reached. Presenting that as a wall overstates it. And a
wall is the only tool available, so there is no graduated friction, and therefore no data
about the moments that precede a slip.

**D7 — Rules die silently.**
A view id belongs to another company's app and gets renamed without notice. When that
happens the rule stops matching and *nothing says so*. Learn Mode exists as the repair, but
it is reactive: it requires the man to notice a feed got through, understand that a rule
drifted, and go find the tool. The system already has everything needed to notice on its own
and does not look.

**D8 — The app's own intelligence never reaches the guard.**
`Analytics.insights()` computes his peak urge hour, his hardest weekday, his leading app and
his leading trigger — and offers `Defence.TightenAtHour` / `Defence.HardenApp` as *manual
suggestions*. The protection layer never reads any of it. Bastion knows a man's 11pm is
dangerous and guards 11pm exactly as hard as 11am.

---

## 2. The model, on one page

```
CATEGORY            what he is avoiding, in his words
   └── SURFACE      one destination:  "Instagram Reels"
          └── SIGNAL   one piece of evidence: view-id | url | title | domain | package

POLICY  =  TARGET  ×  CONDITION  ×  RESPONSE   [ × RISK ESCALATION ]

COVERAGE =  f( chosen categories , installed apps , armed mechanisms )
```

Five nouns, and each has exactly one job:

| Noun | Owns | Chosen by |
|---|---|---|
| **Category** | *what* is being avoided | the user (this is the whole configuration UI) |
| **Surface** | *where* it lives | Bastion's catalogue |
| **Signal** | *how it is recognised* | Bastion's catalogue + Learn Mode |
| **Condition** | *when* a policy is live | the user, as chips on a category |
| **Response** | *how hard Bastion pushes back* | the user, as one slider per category |

The central move: **a man chooses categories; the app resolves categories into apps, and
shows him the resolution.** Per-app configuration survives — but as an *override and an
inspector*, never as the way protection is set up.

---

## 3. Categories — the answer to "how do I choose what gets blocked"

Shipped catalogue. Each is a plain-English thing to avoid; each resolves to surfaces,
domains and installed packages.

| key | Label he reads | Resolves to | Default | Default response |
|---|---|---|---|---|
| `PORN` | Pornography | blocklist domains + keyword heuristic + SafeSearch + installed adult apps + explicit titles | **on** | Close |
| `SHORT_FEED` | Endless feeds | Reels, Shorts, For You, Spotlight, X video, Reddit video — in-app *and* in any browser | **on** | Close |
| `BORDERLINE` | Sexualised scrolling | IG Explore, Reddit NSFW, X media tab, "hot" tabs | off | Pause |
| `DATING` | Dating and hookup | installed dating apps + their sites | off | Close |
| `ANON_CHAT` | Cam sites and anonymous chat | cam/roulette domains + their apps | **on** | Close |
| `ESCALATION` | The ways round | VPN apps, DoH-capable alt browsers, sideload stores, file-hider apps, incognito | off | Pause |
| `PHONE_AT_NIGHT` | The phone after dark | everything already chosen, under a curfew condition | off | Close |

Two design notes that matter:

- **`PHONE_AT_NIGHT` is not really a category — it is a condition preset.** Ship it as a
  category anyway, because that is how a man thinks about it, and implement it as a
  `Condition.Curfew` applied across his other chosen categories. Do not build a second
  scheduling system for it.
- **`ESCALATION` is the category no other blocker ships and the one that decides whether
  any of this survives contact with a bad night.** It does not have to *block* those apps.
  Pausing them, and telling a partner, is usually enough — see §6.

Every category screen shows its **resolution**, computed live, never hardcoded prose:

> **Pornography** — Close
> Blocks 41,802 domains · forces SafeSearch on 9 search engines · closes 2 installed apps
> · reads video titles in 3 apps.
> **1 gap:** Firefox on this phone can resolve DNS itself and bypass the filter. →

---

## 4. Surfaces and Signals — the answer to "for which app"

A **Surface** is one destination a man would name. A **Signal** is one piece of evidence
that he is standing on it. Surfaces have *many* signals, and that redundancy is the point.

```kotlin
Surface(
  id = "ig_reels", category = PORN_ADJACENT/SHORT_FEED, service = "instagram",
  label = "Instagram Reels",
  signals = [
    Signal(VIEW_ID,  "clips_viewer",              scope = "com.instagram.android"),
    Signal(VIEW_ID,  "root_clips_layout",         scope = "com.instagram.android"),
    Signal(VIEW_ID,  "clips_linear_layout_container", scope = "com.instagram.android"),
    Signal(URL,      "instagram.com/reel",        scope = null),   // anywhere
  ]
)
```

What this fixes:

- **D2 disappears.** `scope = null` means "any app". No schema lie, no sentinel packages, no
  "which browser is this" question ever again. A signal that is genuinely app-specific says
  so; one that is a property of the destination does not.
- **Redundancy becomes visible.** When Instagram renames `clips_viewer`, the surface does not
  die — it *degrades*, and can say so: "Instagram Reels: 3 of 4 signals still working, the
  in-app one has not fired in 16 days."
- **Learn Mode gets a home.** Today a captured rule is an orphan row. In v2 the capture flow
  is: *"Which of these were you trying to close?" → pick a surface → the captured id is added
  as a signal to that surface.* Attribution, health tracking and the man's own repair all land
  in the same place.
- **Signals carry `confidence`.** `VIEW_ID` and `URL` are high (they name a destination).
  `TITLE` is low (it is a net, not a wall — as `TitleFilter`'s own comment says). Low-confidence
  signals may be capped at a lower response — a title match should Pause, not Close.

**Signal health** (fixes D7). Every signal records `lastMatchedAt`, and Bastion already knows
foreground time per package (`AppUsageEntity`). A signal whose scope app has been used
30+ times in 14 days and has never matched is *suspected drifted*. Surface that as a calm
card on the Guard screen, with Learn Mode one tap away. **This is the single highest-value
item in the whole document** — it is the difference between a blocker that decays and one
that repairs itself.

---

## 5. Conditions — when a policy is live

Replaces `BlockMode.SCHEDULE` and `BlockMode.TIME_LIMIT`. Composable; a category can carry
several.

| Condition | Params | Notes |
|---|---|---|
| `Always` | — | default |
| `Curfew` | start, end, days CSV | reuse the existing `curfewDaysCsv` semantics |
| `DailyBudget` | minutes | reuse `AppUsageEntity`; budget is per *category*, not per app, so 20 min of "feeds" is 20 minutes across all of them |
| `OpenCount` | opens/day | cheaper and more legible than minutes for feed surfaces |
| `Overnight` | — | charging + screen-off history + local night. A proxy for "in bed", and label it as a proxy |
| `RiskAtLeast` | 1..3 | see §8 |

`DailyBudget` being per-category rather than per-app is a real behaviour change and the right
one: today, closing Instagram at its limit sends a man to TikTok with a fresh 10 minutes.

---

## 6. Responses — the friction ladder

This is the answer to D6. One `Response` per policy, five rungs:

| # | Name | What happens | Data it produces |
|---|---|---|---|
| 0 | **Watch** | Nothing is blocked. Arrivals are counted. | Where he actually goes — before anything is taken away |
| 1 | **Pause** | Full-screen interrupt: 10s, his Why, "Continue" / "Not now" | Continue rate — the leading indicator of a bad night |
| 2 | **Cost** | Continue requires a real act: type one line of his covenant, or a 60s wait. N continues per day, then it becomes Close | What it costs him to get through |
| 3 | **Close** | Current behaviour: home + shield + panic route | Blocked arrivals |
| 4 | **Sealed** | Closed, and the policy cannot be loosened without the cooling-off wait; app suspended where device-owner allows | — |

Rules of the ladder — non-negotiable, they are what keep it honest:

- **Raising is instant. Lowering goes through cooling-off.** This is already the app's law
  (`requestWeakening`, `BlockMode.isWeakerThan`); apply it to response level instead of
  `BlockMode` strictness. `ProtectionLevelTest` / `NoUngatedOffSwitchTest` extend to cover it.
- **Every continue is logged, none is punished.** Continuing past a Pause is data, not a
  failure. It is the same principle as "logging a slip earns points" — the honest act is the
  one being reinforced.
- **Watch is offered first, and offering it is a feature.** A week of Watch produces a
  personalised, evidence-based recommendation instead of a guessed list of seven packages.
  It is also the answer for a man who will otherwise uninstall on day two.
- **Cost never uses shame.** Typing his own covenant line is the mechanism; there is no
  "are you sure you want to give up" copy anywhere.

---

## 7. Mechanism tiers — saying out loud how strong a block is

The README already has an "Honest limits" section. Make that a property of the model rather
than a paragraph.

| Tier | Mechanisms | What defeats it | UI phrase |
|---|---|---|---|
| **A — Structural** | DNS NXDOMAIN, Private DNS, device-owner app suspension | Turning the filter off (which the settings wall + cooling-off can gate) | "Held at the network" |
| **B — Reactive** | Accessibility guard, shield, curfew, lockdown | Disabling the service; the screen is reached before it closes | "Closed on arrival" |
| **C — Advisory** | Grayscale, title filter, Pause / Cost | Tapping continue | "Slows it down" |

Every block receipt names its tier. Every category's resolution shows the *highest* tier it
achieves and what is left at a lower one. A man who knows his blocker is Tier B on Instagram
and Tier A on porn sites is better protected than one who believes both are walls, because
he knows which one still needs him.

---

## 8. Risk — protection that responds

Fixes D8. Everything is computed on-device from data already in the database.

**Inputs** (all local, all already collected):

| Input | Source |
|---|---|
| Hour vs his own urge histogram | `UrgeLogEntity` via `Analytics.peakWindow` |
| Weekday vs his hardest day | `Analytics.strongestDay` |
| Days since last slip vs his own median cycle | `DayLogEntity` |
| Continues past Pause/Cost in the last 3 hours | new `policy_event` table |
| Guarded-app opens in quick succession after a late unlock | accessibility service, in-memory |
| Self-reported | the "I'm having an urge" button, the panic tile |

**Score:** 0 (calm) → 3 (high). Each input contributes at most 1; the score decays one level
per two clear hours and resets on sleep. Keep the arithmetic in a pure `RiskModel` object
with no Android imports, like `FeedSurface` and `GuardedScreens` — it must be testable on a
laptop.

**Effect:** a policy may carry `escalateAtRisk`. At or above that level its response rises by
one rung, capped at Close. Also at risk 3: Bastion *offers* Hold the Line rather than waiting
to be reached for.

**Guardrails — these are the difference between adaptive and creepy:**
1. Risk **never lowers** a response. It only tightens, and only within the ceiling he set.
2. Every escalated block carries its reason in plain words: *"Closed at Cost instead of
   Pause — it's 11:40pm, which is your hardest hour, and this is the third pause tonight."*
3. Risk is visible on the Guard screen at all times, with its inputs listed. Nothing is
   inferred behind his back.
4. It is never phrased as a prediction about him. "Your hardest hour" is a fact about his log.
   "You're about to slip" is a claim Bastion has no right to make.

---

## 9. Coverage and Gaps — making the headline true

Replaces the 7-pip permission meter with two honest numbers.

**Armed** — which mechanisms are live. Keep `GuardLayer` and `rememberGuardLayers` almost
as-is; they are good. Rename to "What's switched on".

**Covered** — per chosen category, the share of its known surfaces that is *enforceable right
now*, given which mechanisms are armed and which apps are installed. A category with an
uninstalled app does not count against it. A category whose only mechanism is a disabled
accessibility service reads 0%.

**Gaps** — generated by scanning installed packages against the catalogue. These are the
sentences no blocker on the Play Store will say, and they are the reason to build this:

- "Firefox is installed and can resolve DNS itself. The website filter does not reach it."
- "You have a second browser (Brave) with no rules on it."
- "Telegram is installed. Bastion cannot see inside channels."
- "Chrome's incognito is on. Nothing is being logged there, including by you."
- "Installing from unknown sources is enabled — that is how a blocked app comes back."
- "Your phone is on IPv6. This version only intercepts IPv4 DNS."
- "3 of the 7 apps you blocked have not been opened in 30 days. 2 you use daily are not blocked."

Every gap is a card with one action. **Gaps replace the concept of "finish setting up"** —
setup is never finished, it is a list that shortens.

---

## 10. Data model

Room is at **version 7**, with no destructive fallback and `MigrationChainTest` enforcing the
chain. That constraint is a gift here: it forces the migration to be written properly.

### New tables (version 8)

```kotlin
@Entity("protection_category")
data class CategoryEntity(
  @PrimaryKey val key: String,          // PORN, SHORT_FEED, ...
  val chosen: Boolean,
  val response: Int,                    // 0..4
  val escalateAtRisk: Int?,             // null = never escalate
  val updatedAt: Long,
)

@Entity("surface")
data class SurfaceEntity(
  @PrimaryKey val id: String,           // "ig_reels"
  val categoryKey: String,
  val serviceKey: String,               // "instagram" — a service, honestly named
  val label: String,
  val builtIn: Boolean,
  val enabled: Boolean,
  val updatedAt: Long,
)

@Entity("surface_signal", indices = [Index("surfaceId"), Index("scopePackage")])
data class SignalEntity(
  @PrimaryKey val id: String,
  val surfaceId: String,
  val matchType: MatchType,             // VIEW_ID | TEXT | CONTENT_DESC | URL | TITLE | PACKAGE
  val matchValue: String,
  val scopePackage: String?,            // null = anywhere
  val confidence: Int,                  // 0 low .. 2 high; caps the response
  val enabled: Boolean,
  val builtIn: Boolean,
  val lastMatchedAt: Long,              // signal health
  val updatedAt: Long,
)

@Entity("policy")
data class PolicyEntity(
  @PrimaryKey val id: String,
  val targetType: TargetType,           // CATEGORY | SURFACE | APP
  val targetKey: String,
  val conditionType: ConditionType,     // ALWAYS | CURFEW | DAILY_BUDGET | OPEN_COUNT | OVERNIGHT | RISK_AT_LEAST
  val conditionParams: String,          // JSON, kotlinx.serialization
  val response: Int,
  val escalateAtRisk: Int?,
  val enabled: Boolean,
  val source: PolicySource,             // DEFAULT | USER | STANCE
  val updatedAt: Long,
)

@Entity("policy_event", indices = [Index("ts")])
data class PolicyEventEntity(
  @PrimaryKey val id: String,
  val ts: Long,
  val policyId: String?,
  val surfaceId: String?,
  val packageName: String,
  val outcome: Outcome,                 // ARRIVED | PAUSED | CONTINUED | COST_PAID | CLOSED
  val riskAtTime: Int,
)
```

### Migration 7 → 8 — additive only, nothing dropped

1. Create the five tables.
2. Seed the catalogue: categories, surfaces, signals — the signal rows are
   `builtInFeedRules()` re-homed, so the values are already proven.
3. **`feed_rule` → `surface_signal`:** every row whose `id` matches a known built-in maps to
   its surface. Every *user-created* row (`builtIn = false`) attaches to a surface named
   "Learned in {app}" under `SHORT_FEED`, so nothing a man captured himself is lost.
   A user row that had been switched off stays off.
4. **`guarded_app` → `policy`:** one row per app.
   | old | new |
   |---|---|
   | `FULL` | `APP` / `ALWAYS` / Close |
   | `SCHEDULE` | `APP` / `CURFEW(start,end)` / Close |
   | `TIME_LIMIT` | `APP` / `DAILY_BUDGET(minutes)` / Close |
   | `FEED_ONLY` | `SURFACE`-targeted policies for that service's surfaces / `ALWAYS` / Close |
5. **Do not drop `feed_rule` or `guarded_app` in this migration.** Leave them, unread, for one
   release. If v2 has to be rolled back, the data is still there. Drop them in 8 → 9 once a
   release has shipped clean. `MigrationChainTest` keeps this honest.

---

## 11. The decision pipeline

Today the decision lives inside `evaluate()` (`BastionAccessibilityService.kt:638`), tangled
with lockdown checks, learn mode, veil state and grayscale. Pull it out. The repo already
proves this pattern works — `FeedSurface` and `GuardedScreens` are pure because they *had*
to be testable on a laptop, and they are the two most reliable pieces of the app.

```
AccessibilityEvent
  → Context     ( package, screen evidence, node geometry, time, risk )
  → Resolver    ( which SURFACES are present? — signals matched, geometry gate applied )
  → Engine      ( which POLICIES target those surfaces or this app,
                  and whose CONDITIONS hold right now? )
  → Decision    ( strongest response wins; low-confidence signals capped )
  → Enforcer    ( executes the response at the highest available TIER )
  → Receipt     ( PolicyEvent written; the "why" sentence rendered on demand )
```

`Resolver`, `Engine` and `Decision` are pure Kotlin with zero Android imports. `Enforcer` is
the only part that touches the service. This makes the *entire* protection logic unit-testable
without a phone — which is the thing that currently cannot be done and is why the hard bugs
(the inline-reel false positive, the stories viewer, the Messenger web view) were all found
by the user rather than by a test.

**Keep unchanged:** the geometry gate (`coversWindow`, `EDGE_SLACK`, `scrollsVertically`),
`looksLikeUrl`'s privacy boundary, `isBrowserChrome`, the `GuardedScreens` wall detection.
Those are hard-won and correct. They become inputs to `Resolver`, not casualties of it.

---

## 12. UI — three screens

**1. Shield (the Guard tab landing).** One true sentence, then the evidence.

> **Covered: porn, endless feeds, cam sites.** Two gaps.
> *Switched on: 6 of 7 · Risk: low*
> [gap card] [gap card] [drifted-signal card]

**2. What I'm avoiding** — the primary configuration, and the only place a man normally goes.
One row per category: chosen, a response slider, condition chips, and the live resolution
line. Choosing a category does *everything* the category needs — domains, surfaces, SafeSearch,
installed apps — in one tap. That is the same insight `ProtectionSection` already had for apps,
raised one level to where it belongs.

**3. Apps** — keep the current card list, reframed. Every *installed* app, not only the
guarded ones, each showing what applies to it and **where it came from**:

> **Instagram** — Reels closed *(from: Endless feeds)* · whole app 23:00–06:00 *(from: The phone after dark)* · 20 min/day shared with 3 apps
> [Make an exception for Instagram]

That derivation line is the answer to "how do I choose what's blocked for which app": he
doesn't, mostly — and when he does, he can see exactly what he is overriding.

**4. The receipt.** Every shield gets a "Why did this close?" link that shows: the surface, the
signal that matched, the policy, the condition that was true, the tier, and the risk level.
It costs almost nothing and it is the entire difference between a tool a man trusts and one
he suspects.

---

## 13. Onboarding — stances, not a list of packages

Replace `SUGGESTED_PACKAGES` with three stances, each a bundle of category choices, responses
and conditions:

- **Watch first** — everything at Watch for seven days, nothing blocked. Then Bastion proposes
  a stance built from what it saw. *Recommend this by default.* It is the honest opening
  move and it is the one that stops the day-two uninstall.
- **Standard** — PORN + SHORT_FEED + ANON_CHAT at Close, everything else off.
- **Strict** — all categories on, BORDERLINE and ESCALATION at Pause, curfew 23:00–06:00,
  cooling-off at 24h, Sealed on PORN.

Plus one button that exists forever: **"Tighten everything one notch."** Instant, because
tightening always is.

---

## 14. Tests to write (the repo has 26; these are the new ones)

Named in the repo's existing style, all pure-JVM:

- `PolicyEngineTest` — strongest-response-wins; overlapping category and app policies; a
  disabled category does not leak; a condition that is false contributes nothing.
- `ConditionTest` — curfew across midnight and across days; budget shared across a category;
  open-count reset at local midnight.
- `ResponseLadderTest` — raising is instant, lowering enqueues a cooling-off change; no path
  lowers without one (extends `NoUngatedOffSwitchTest`).
- `ConfidenceCapTest` — a `TITLE` signal can never produce Close on its own.
- `RiskModelTest` — each input contributes at most 1; decay; reset; risk never lowers a response.
- `SignalHealthTest` — drift detection fires only after N uses with zero matches, and never
  for an app that is not installed.
- `CoverageTest` — an uninstalled app does not reduce coverage; a disarmed mechanism does.
- `PolicyMigrationTest` — all four `BlockMode` values land on the right policy shape; a
  user-created feed rule survives with its enabled state; `feed_rule` and `guarded_app` still
  exist after 7→8.
- Keep every existing `FeedSurface` / `GuardedScreens` test untouched and passing. If any of
  them has to change, the refactor is wrong.

---

## 15. Phasing — four shippable steps

Each phase is a release that stands on its own. Do not start the next until the previous has
run on a real phone for a week.

**Phase 1 — Policies (the structural fix).**
New tables, migration 7→8, the pure decision pipeline, existing UI rewired on top of it.
No new user-visible features. **This alone kills D1 and D2** and makes everything after it
cheap. Success = the app behaves identically and the whole guard is unit-testable.

**Phase 2 — The ladder and the receipt.**
Watch / Pause / Cost / Close / Sealed, `policy_event`, the "why did this close" sheet, tiers
named in the UI. Success = a man can read why anything happened.

**Phase 3 — Categories, coverage and gaps.**
The catalogue, the new Guard screen, installed-app scanning, signal-health drift detection,
the Watch-first stance. **Signal health can be pulled forward into Phase 1 if you want the
highest-value item early** — it depends on nothing else.

**Phase 4 — Risk.**
`RiskModel`, escalation, the risk row on the Guard screen with its inputs shown.

---

## 16. Anti-goals — things to deliberately not do

- **No cloud, no account, no sync.** The whole design fits on one device. Keep it there.
- **No reading message content, ever.** `looksLikeUrl`'s boundary is a promise. Nothing in
  categories, risk or coverage may cross it.
- **No shame, no red, no streak-loss framing.** The ladder is friction, not punishment. Amber,
  never red — including in the receipt and the gap cards.
- **No prediction claims about the man.** Report facts about his log; never assert what he is
  about to do.
- **No silent escalation.** If risk tightened something, the shield says so in that moment.
- **No enum that mixes dimensions.** `BlockMode` is the mistake this whole document exists to
  correct. If a new enum ever holds "what" and "when" together, it is the same bug wearing a
  different name.
- **Do not remove Learn Mode.** It gets better here, not smaller: it becomes the way a man
  repairs a named surface rather than a way to create an orphan rule.
