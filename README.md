# Stronger

A workout tracker for Android in the spirit of Strong, built with Jetpack Compose and Material 3 Expressive so it feels at home on a Pixel: wallpaper colours (Material You), expressive springs and shapes, and Google Sans Flex type.

## Features

**Workout**
- Start an empty workout or one from a template; resume a workout in progress from anywhere, even after the app is killed.
- Weekly goal ring, a 7-day strip and a week streak.
- One reminder, half an hour before you usually train, only on days when skipping would break your weekly goal or week streak.
- "Up next": the template your rotation says comes next is listed first, and the info button beside "Templates" explains how that's worked out. Template cards show how long each usually takes.
- A recap of last month (workouts, time, volume, records, biggest est. 1RM gains, body-weight change) for the first week of each month.
- Templates: create, edit, duplicate, delete, preview before starting. Save any past workout as a template, or update a template with today's sets when you finish.

**Live workout**
- Per set: weight × reps, reps only, time, or distance + time, depending on the exercise category. Assisted/weighted bodyweight show −kg/+kg.
- "Previous" column with last time's matching set (warm-ups pair with warm-ups). Tap it to copy; blank fields are filled from it when you tick the set.
- Set types: warm-up, drop set, failure. Swipe a set left to delete it.
- Rest timer starts automatically when you complete a set (per-exercise or default rest, off per exercise). ±15 s and skip in-app and from the notification.
- Supersets (rest starts after the last exercise in the round), exercise notes (one-time for this workout, or sticky so they show every time; past notes appear in the exercise history), reorder, replace, remove.
- Live PR detection: a trophy appears on the set the moment you beat a record. Before you lift, an outlined trophy marks sets whose numbers would set one, and "+1 rep" marks sets one rep short of one.
- Typo check: finishing with a set far beyond your best (225 kg for 22.5, 100 reps for 10) asks whether it's right before it becomes an unbeatable record. Editing or deleting an old workout re-awards trophies on the workouts after it.
- Estimated finish time ("done ~7:40 PM") from your usual pace per set, in the workout header and notification.
- Forgot to finish? After an hour with no set ticked you get a "Still training?" notification, and a workout finished more than 30 minutes after its last set ends one rest after that set.
- Effort: after ticking a working set, a "Reps left?" row asks for reps in reserve (0–4+), with a guess from your best est. 1RM marked "~". Only tapped answers are saved; change them from the set-number menu, or turn the question off in Settings. Easy sets (3+ in reserve) make targets jump twice as far, and effort counts towards plateau detection, so a lift that's getting harder shows up before the numbers drop. Strong's RPE column imports as RIR.
- Rep targets (double progression): give an exercise a rep range and each working set suggests last time's weight with one more rep; once every set reaches the top, the weight goes up (2.5 kg / 5 lb by default, or your own step) and reps reset. Assisted exercises take assistance off instead. Targets replace stale template numbers.
- "Ready to progress" badge: an exercise earns it when every working set meets its goal (the top of the rep range, the reps a template started the set with, or the time a duration set's timer ran to), and keeps it until a session falls short again, which the first heavier or longer one usually does.
- Set timer for duration sets: counts up to the time typed into the set (else last time's), with a tick and a short buzz where last time ended.
- Ongoing notification, promoted to an Android 16 Live Update, with rest progress, "up next", ±15 s, skip and "Complete set". Vibration and sound when rest is over; the sound ducks music instead of pausing it.
- Keeps the screen on while a workout is open (setting).
- Finishing shows a celebration screen with duration, volume, sets and new records.

**Exercises**
- 155 built-in exercises, named like Strong's ("Bench Press (Barbell)"), plus your own custom ones.
- Search, body-part filters, sort by name, frequency or recency, A–Z headers.
- Exercise page: progress chart (est. 1RM, max weight, volume; reps or time for other categories), full history, records and a rep-records table.
- Plateau detection: each exercise shows whether it's progressing, stalled (no new best in 3+ sessions) or going backwards, with what to try, plus the rep targets and next session's sets. When several lifts stall at once, the Workout tab suggests a deload week.

**History & profile**
- Month calendar of workout days and month-grouped workout cards with best sets and PR counts.
- Workout detail with every set and its 1RM; edit, delete, "do it again" or save as template.
- Profile: all-time totals, workouts-per-week chart against your goal, recent records, plate calculator.

**Body and health**
- Body weight with a smoothed trend (so daily swings don't mislead) and 7/30/90-day changes, plus body fat and tape measurements (waist, chest, arms, …) in cm or inches.
- Health Connect: finished workouts are written as exercise sessions (strength, calisthenics or the cardio type), body weight and body fat go both ways, and edits and deletes follow. Weight from scales and other apps comes in, including deletions. The profile shows the last week's cardio against 150 minutes, steps, resting heart rate and sleep.

**Data**
- Import from Strong: in Strong go to Settings → Export data, then pick the CSV in Stronger's Settings. Both the older comma and newer semicolon formats work. Exercises are matched by name, unknown ones become custom exercises, and records are recalculated. Re-importing skips workouts already present.
- Back up everything (including measurements) to a JSON file and restore it on any phone.
- kg or lbs (stored internally as kg), default rest, weekly goal, bar weight and available plates.

## Build

Requires JDK 21 and the Android SDK (compileSdk 37). The phone needs Android 13+.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew testDebugUnitTest assembleRelease --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs locally.

## Layout

```
app/src/main/java/app/stronger/
  data/      Room entities + DAOs, repository, the live workout (ActiveWorkout), pure session edits
             and records (SessionOps), targets, plateaus and trends (Insights), settings (DataStore), Strong CSV import and JSON backup
  health/    HealthSync (Health Connect read/write) and the privacy screen it links to
  service/   WorkoutService: foreground notification / Live Update and rest alerts
  ui/        Navigation, theme, formatting, charts and one package per tab
```

## Fonts

`res/font/gsf_*.ttf` are static instances of [Google Sans Flex](https://github.com/google/fonts/tree/main/ofl/googlesansflex) (OFL), cut with fontTools at roundness 100 (display, headline, title large) and 50 (everything else), weights 400–800, subset to Latin:

```bash
fonttools varLib.instancer GoogleSansFlex.ttf wght=700 ROND=100 opsz=18 wdth=100 slnt=0 --static -o i.ttf
pyftsubset i.ttf --unicodes="U+0020-007E,U+00A0-024F,U+2010-2027,U+2030-205E,U+2190-21FF,U+2212,U+2022,U+20AC" --layout-features='*' --output-file=gsf_r100_w700.ttf
```

Static files are used because Compose didn't apply the variable font's axes on this device.
