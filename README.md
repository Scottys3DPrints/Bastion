# Bastion

An Android app that helps men quit porn by building identity rather than trading in shame.
Faith Mode and Discipline Mode share one engine and two vocabularies.

Built to be **installed directly onto a phone** — no Play Store, no account, no server.
Everything lives on the device.

---

## Install it on your phone

Releases are built and signed by GitHub Actions and attached to a Release, so the phone
never needs a cable or a computer.

1. Open the repo's **Releases** page in the phone's browser.
2. Tap the `bastion-<version>.apk` attached to the newest release.
3. Allow your browser to install unknown apps when prompted.
4. Open Bastion.

Play Protect may show a "scan this app?" dialog. That is expected for any self-signed APK
and is not a warning about this app specifically.

**Already have Bastion installed? Don't use that page.** Open the app and go to
**Settings → Updates → Check for updates**. It installs over the top and keeps your
covenant, streak, rank and every log.

---

## Turn the guards on

Bastion works the moment it opens, but three things need switching on by hand:

**1. Bastion Guard** — Settings → Accessibility → Bastion Guard.
This is what makes the headline feature possible: Instagram still opens so you can message
someone, but the instant Reels appears the door closes. Same for YouTube Shorts and the
TikTok For You page.

**2. Content filter** — Guard tab → Content filter.
Android asks for VPN permission. Nothing is sent anywhere: the tunnel advertises a fake
resolver and routes only that one address, so the only packets Bastion ever sees are DNS
lookups. Blocked domains get NXDOMAIN; everything else is forwarded to Cloudflare's family
resolver untouched.

**3. Grayscale (optional)** — needs a permission Android only grants over USB. Sideloading
is what makes this available to you at all:

```bash
adb shell pm grant com.bastion.app android.permission.WRITE_SECURE_SETTINGS
```

Without it, Bastion falls back to a dimming veil and says so rather than pretending.

Also worth adding: the **Watchtower widget** on your home screen, and the **Hold the Line**
tile in your quick-settings pull-down. An urge does not wait while you hunt for an icon.

---

## What it actually does

| Pillar | What ships |
|---|---|
| **Guard** | Per-screen feed blocking (Reels / Shorts / For You / Spotlight), full and scheduled app blocks, daily time limits, DNS content filter, always-filtered browser, cooling-off lock |
| **Track** | Rank that never resets, streak, urge and mood logging, pattern analytics with one-tap defences, benefit timeline with honest confidence labels |
| **Motivate** | 30 days of Daily Briefs in both modes, 24-lesson library, 8 structured challenges, badges |
| **Become** | 30-habit Regimen, the "Man You're Becoming" profile, offline Mentor, accountability partner and check-ins |

### The design decisions that matter

**Rank over streak.** Rank is cumulative and never resets. It is earned by clean days *plus*
habits, challenges, check-ins and resisted urges. A slip restarts the streak and does not
touch the rank. This is the single choice that stops people deleting the app after a bad
night.

**Logging a slip earns points.** Not a reward for slipping — a reward for the honesty and
analysis that follow it, which is the behaviour that actually predicts recovery. Nothing in
Bastion ever subtracts.

**Amber, never red.** There is no alarm state anywhere in the app, including the relapse
flow.

**Feed rules live in the database, not the code.** When Instagram reshuffles its layout and
a rule stops firing, open Guard → Feed rules → Learn, go to the offending screen, and
recapture it in seconds. No new build needed.

---

## Honest limits

- Apps that ship their own DNS-over-HTTPS resolver bypass the content filter. The Guard
  service and the Bastion browser are the other two layers; no single one is sufficient.
- The filter works on whole domains, not individual pages.
- IPv6 resolvers are not intercepted in this version.
- The built-in feed rules reference view identifiers inside other companies' apps. They
  will drift. Learn Mode exists precisely because of that.
- The Mentor is a curated script, not a language model. It trades cleverness for working
  offline at 1am with nothing leaving the phone. It is **not** therapy and says so, and it
  routes anything resembling crisis straight to real human help.

## Privacy

No network calls except forwarding DNS lookups to your chosen resolver. No analytics, no
account, no telemetry. Cloud backup and device transfer are disabled in the manifest, so
the database cannot leave via Google's backup either. The accessibility service reads
*which screen* is open and nothing else — never message contents — and Learn Mode captures
view identifiers only, never text.

---

## Install once, never again

Bastion is installed a single time and updated in place forever. Your covenant, your
signature, your Why video, your streak, your rank and every log survive every update.

### Shipping a new version

```bash
git tag v0.2.0 && git push origin v0.2.0
```

That is the whole release process. The tag triggers `.github/workflows/build-apk.yml`,
which runs the tests, builds, signs with your key from repo secrets, prints the signing
fingerprint so you can confirm it, and publishes a Release with two assets:

```
bastion-0.2.0.apk      the signed build
bastion-update.json    the manifest the phone polls
```

### How the phone updates itself

The app ships pointing at:

```
https://github.com/<owner>/<repo>/releases/latest/download/bastion-update.json
```

`latest/download/` always resolves to the newest release, so that address never changes no
matter how many versions ship — the phone is configured once and then forgotten. Tap
**Settings → Updates → Check for updates**, and Bastion downloads the APK, verifies its
SHA-256 against the manifest, and installs it over itself.

Nothing is fetched until you press Check. **Check automatically** is off by default, so the
app still makes no network calls of its own accord beyond DNS.

### One-time repo setup

The signing secrets are what make updates possible; without them CI falls back to a
throwaway debug key that differs every run and can never upgrade anything.

```bash
gh secret set BASTION_KEYSTORE_BASE64 < keystore.b64
```

Set all four — `BASTION_KEYSTORE_BASE64`, `BASTION_KEYSTORE_PASSWORD`, `BASTION_KEY_ALIAS`,
`BASTION_KEY_PASSWORD` — or run `setup-github.ps1`, which does the repo, the secrets and
the first push in one go.

After the first tagged run, open the **Report signing identity** step in the Actions log.
You want a real `SHA-256 digest:` line with no warning about a throwaway debug key
underneath it. That is the proof the secrets took effect.

### Without GitHub

`update.bat` rebuilds and installs straight to a plugged-in phone over adb, keeping data.
`publish-update.bat` produces the same two `dist/` files locally if you would rather host
them elsewhere.

### What makes it safe

**Room migrations, and no destructive fallback.** `Migrations.kt` holds every schema change
in order. `BastionDatabase` deliberately does *not* call `fallbackToDestructiveMigration()`
— if a version ever shipped without its migration, it would crash loudly rather than
silently erase a man's journey. A crash is recoverable; a wipe is not.

`MigrationChainTest` enforces this at build time: bump the database version without adding a
migration and the build fails on your laptop, where it costs nothing. This was verified by
deliberately bumping to version 2 and confirming the test failed.

### Keep the keystore

`bastion-release.jks` and `keystore.properties` are gitignored and **not recoverable**.
Android refuses to update an app signed with a different key, and the only workaround would
be uninstalling — which destroys the database. Back both up somewhere safe. Everything else
in this repo can be rebuilt; these cannot.

## Rebuilding

```bash
./gradlew assembleRelease
```

```bash
./gradlew testDebugUnitTest
```

26 unit tests cover content integrity (every bundled JSON decodes with the real models, the
crisis intent outranks everything and always names a helpline), the domain filter (including
that health, education and recovery sites survive the keyword heuristic), rank monotonicity,
and the migration chain.

---

## Not a medical device

Bastion is a support tool, not treatment. Compulsive use often sits alongside anxiety,
depression, trauma or loneliness, and those deserve a real clinician. The library and the
Mentor both point to professional help, and the crisis path never tries to counsel.
