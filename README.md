# Rep Coach

Personal Android strength-training tracker with an AI coach. It plans each gym session from your
history, today's energy, the time you have and your recovery data, then logs every set.

## Install on your phone

1. On your phone, open <https://github.com/Dred101/strength-coach/releases/latest> (signed in to GitHub).
2. Tap **RepCoach.apk** to download it, then open it and allow your browser to install apps when asked.
3. New versions install over the old one; your data stays on the phone.

Phones with Android's Advanced Protection turned on only install apps from the Play Store. For those,
upload `RepCoach.aab` from the same release to a Google Play internal test track and install from Play.

## First run

1. **Settings, Import Hevy CSV.** In Hevy: Profile, Settings, Export & Import Data, Export Workouts.
2. **Settings, Claude API key.** Create one at console.anthropic.com. Without a key the app still plans
   sessions with its built-in rules.
3. **Settings, Connect Health Connect.** Your band's app (Fitbit or Google Health) must be syncing into
   Health Connect for sleep, resting heart rate and HRV to show up.

## How the coach works

- `core/` is plain Kotlin with no Android code, so it can be tested on any machine (`cd core && ../gradlew test`).
  - `HevyCsv` reads Hevy's export.
  - `Progression` does the numbers: estimated 1RM (Epley, counting reps in reserve), double
    progression within each exercise's rep range, energy adjustments, plateau detection and weekly volume.
  - `Planner` builds a session without AI: three full-body templates a week, then variety sessions that
    favour muscles with the least volume, sized to the minutes available.
  - `ClaudeCoach` sends Claude a summary of all of that plus the rules engine's draft, and asks for the
    session as structured JSON. The engine's weights override Claude's for any exercise with history.
- `app/` is the Jetpack Compose app: check-in, plan, workout logging with a rest timer, history,
  progress charts and settings. Data is stored on the phone in `workouts.json`.

## Building

GitHub Actions builds the app on every push (`.github/workflows/build.yml`) and publishes `RepCoach.apk`
as a release for each build on `main`. To build locally you need the Android SDK: `./gradlew :app:assembleRelease`.

The app is signed with `app/personal.keystore`, a key for personal sideloading only. Replace it with a
private key kept out of the repo before publishing anywhere.
