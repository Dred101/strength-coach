# Rep Coach

Personal Android strength-training tracker with an AI coach. It plans each gym session from your
history, today's energy, the time you have and your recovery data, then logs every set.

## Install on your phone

1. On your phone, open <https://github.com/Dred101/strength-coach/releases/latest> (signed in to GitHub).
2. Tap **RepCoach.apk** to download it, then open it and allow your browser to install apps when asked.
3. New versions install over the old one; your data stays on the phone. Don't uninstall first, and always
   update the same way you first installed (this file, or Play, not a mix): switching makes Android
   remove the old copy and its data.
4. Turn on **Settings, Automatic backup** and save the file in Google Drive. If the app ever starts empty,
   the Today screen offers **Restore my backup**; pick that file.

Phones with Android's Advanced Protection turned on only install apps from the Play Store. For those,
upload `RepCoach.aab` from the same release to a Google Play internal test track and install from Play.

## First run

1. **Settings, Import Hevy CSV.** In Hevy: Profile, Settings, Export & Import Data, Export Workouts.
2. **Settings, Claude API key.** Create one at console.anthropic.com. Without a key the app still plans
   sessions with its built-in rules.
3. **Optional: Settings, Hevy sync.** To keep logging in Hevy, paste your Hevy API key (needs Hevy Pro;
   hevy.com, Settings, Developer). Rep Coach pulls new Hevy workouts when it opens and before each plan,
   and **Send to Hevy** on a plan creates a "Rep Coach" routine in Hevy to follow and log.
4. **Settings, Connect Health Connect.** Your band's app (Fitbit or Google Health) must be syncing into
   Health Connect for sleep, resting heart rate and HRV to show up.

## How the coach works

- `core/` is plain Kotlin with no Android code, so it can be tested on any machine (`cd core && ../gradlew test`).
  - `HevyCsv` reads Hevy's export; `HevyApi` syncs with Hevy's public API (workout events in,
    plans out as a routine that is replaced each time).
  - `Progression` does the numbers: estimated 1RM (Epley, counting reps in reserve), double
    progression within each exercise's rep range, energy adjustments, plateau detection and weekly volume.
  - `Planner` builds a session without AI: three full-body templates a week, then variety sessions that
    favour muscles with the least volume, sized to the minutes available. `MuscleRecovery` keeps a muscle
    that did hard sets in the last 48 hours off hard work, and turns the plan into a rest day when most
    of the body is still recovering.
  - `Why` gathers the facts, rules and research behind any plan, exercise or set for **Ask why**, where
    `ClaudeCoach.ask` answers follow-up questions and challenges.
  - `ClaudeCoach` sends Claude a summary of all of that plus the rules engine's draft, and asks for the
    session as structured JSON. The engine's weights override Claude's for any exercise with history.
- `app/` is the Jetpack Compose app: check-in, plan, workout logging with a rest timer, history,
  progress charts and settings. Data is stored on the phone in `workouts.json`.

## Building

GitHub Actions builds the app on every push (`.github/workflows/build.yml`) and publishes `RepCoach.apk`
as a release for each build on `main`. To build locally you need the Android SDK: `./gradlew :app:assembleRelease`.

The app is signed with `app/personal.keystore`, a key for personal sideloading only. Replace it with a
private key kept out of the repo before publishing anywhere.

## The research behind the coaching

The coaching rules and Claude's instructions follow peer-reviewed studies on volume, frequency, rest, effort and deloads. See [docs/RESEARCH.md](docs/RESEARCH.md) for each rule and its source.
