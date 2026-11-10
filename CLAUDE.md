# Stronger

Android workout tracker (Kotlin, Compose, Material 3 Expressive). Features and layout are in README.md.

## Build and test

The machine is low on RAM: always use these flags and never leave daemons running. Never start an emulator.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m \
  -Pkotlin.compiler.execution.strategy=in-process -q 2>&1 | grep -v "^w: " | tail -30
```

## Device

Test on the user's Pixel 9 over wireless adb. If `adb devices` shows nothing, ask the user to reconnect.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p app.stronger -c android.intent.category.LAUNCHER 1
```

`scripts/release.sh` does all of this in one go (tests, release build, connect, install, launch); add `--publish` to bump the version, commit, tag, push and create the GitHub release. See `--help`. `--publish` needs a clean tree, so commit first.

Prefer `uiautomator dump` for finding tap targets; shrink screenshots before reading them.

## Notes

- M3 Expressive APIs come from material3 1.5.0-beta01. Its default emphasized type styles are all Medium, so Theme.kt sets the spec weights (Bold for title medium/small and labels).
- Don't put `SwipeToDismissBox` rows inside a `Modifier.height(IntrinsicSize.Min)` parent: it loops forever in layout and ANRs.
- The live workout is `ActiveWorkout` (mirrored to `files/active_session.json`); templates and history edits use `LocalEditor`. Both implement `SessionStore`, and all edits are pure functions in `SessionOps.kt`.
- Weights are stored in kg; convert only for display/input.
- Room schema changes need a version bump plus an `AutoMigration` (schemas are exported to `app/schemas`); the phone has real data.
- `ModalBottomSheet`s with text fields need `skipPartiallyExpanded = true` and `imePadding()`, or the keyboard hides their buttons.
- `SessionSet.goal` / `SetEntity.goal` is what a set aimed for (reps, or seconds for duration). It's captured when a workout starts and when a set timer starts, and `readyToProgress` reads it.
- Health Connect writes use `clientRecordId`s (`workout-<id>`, `measurement-<id>`) so re-syncs replace rather than duplicate.
