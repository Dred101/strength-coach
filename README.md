# Rep Coach

Personal Android strength coach. It gives advice and structure, not tracking: you log your workouts
in Hevy, and Rep Coach designs your programme, recommends each session from your Hevy history,
today's energy and the time you have, and lets you ask the coach about any workout it recommends.

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
3. **Optional: Settings, Hevy sync.** With Hevy Pro, paste your Hevy API key (hevy.com, Settings,
   Developer). Rep Coach pulls new Hevy workouts when it opens and before each plan, and **Send to Hevy**
   on a plan creates a "Rep Coach" routine in Hevy to follow and log. Without Pro, import a fresh Hevy
   export from the Today tab now and then.

## Using it

- **Today:** plan a session (energy and minutes), and see every recommended workout, marked done once
  it shows up in your Hevy history.
- **A recommended workout:** each exercise with its reason, **Why?** for the facts and research behind it,
  **How it went** (the plan next to what you logged in Hevy), and a chat with the coach about that workout.
  Ask for a change (a swap, less time, a sore joint) and the coach updates the workout.
- **Programme:** your 8-week block, Full Body A and B, why each lift is there and its next target.
- **Coach:** a general chat about your training, programme and progress.

## How the coach works

- `core/` is plain Kotlin with no Android code, so it can be tested on any machine (`cd core && ../gradlew test`).
  - `HevyCsv` reads Hevy's export; `HevyApi` syncs with Hevy's public API (workout events in,
    plans out as a routine that is replaced each time).
  - `Progression` does the numbers: estimated 1RM (Epley, counting reps in reserve), double
    progression within each exercise's rep range, energy adjustments, plateau detection and weekly volume.
  - `Programme` holds the week's two fixed sessions, Full Body A and B (about 75 minutes, compound-led),
    designed from the lifts in your history. Main lifts stay for an 8-week block; a new block rotates the
    supporting lifts. `Planner` plans A or B when due, and mixed sessions with different exercises on
    any other day, favouring the muscles with the least volume, sized to the minutes available. `MuscleRecovery` keeps a muscle
    that did hard sets recently off hard work (36 hours for 3 to 5 sets, 48 after 6+ sets or sets to failure), and turns the plan into a rest day when most
    of the body is still recovering.
  - `Why` gathers the facts, rules and research behind any plan or exercise. `ClaudeCoach.discussPlan`
    answers questions about one workout (and returns a revised workout when asked to change it);
    `ClaudeCoach.ask` answers the general coach chat from `CoachContext.overview`.
  - `PlanReview` pairs each recommended workout with the Hevy workout that carried it out and compares
    them exercise by exercise.
  - `ClaudeCoach` sends Claude a summary of all of that plus the rules engine's draft, and asks for the
    session as structured JSON. The engine's weights override Claude's for any exercise with history.
- `app/` is the Jetpack Compose app: check-in, recommended workouts with their chats, programme, coach
  chat, history, progress charts and settings. Data is stored on the phone in `workouts.json`,
  `plans.json` and `coach-chat.json`.

## Building

GitHub Actions builds the app on every push (`.github/workflows/build.yml`) and publishes `RepCoach.apk`
as a release for each build on `main`. To build locally you need the Android SDK: `./gradlew :app:assembleRelease`.

The app is signed with `app/personal.keystore`, a key for personal sideloading only. Replace it with a
private key kept out of the repo before publishing anywhere.

## The research behind the coaching

The coaching rules and Claude's instructions follow peer-reviewed studies on volume, frequency, rest, effort and deloads. See [docs/RESEARCH.md](docs/RESEARCH.md) for each rule and its source.
