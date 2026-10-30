# Stronger

A workout tracker for Android in the spirit of Strong, built with Jetpack Compose and Material 3 Expressive so it feels at home on a Pixel: wallpaper colours (Material You), expressive springs and shapes, and Google Sans Flex type.

## Features

**Workout**
- Start an empty workout or one from a template; resume a workout in progress from anywhere, even after the app is killed.
- Weekly goal ring, a 7-day strip and a week streak.
- Templates: create, edit, duplicate, delete, preview before starting. Save any past workout as a template, or update a template with today's sets when you finish.

**Live workout**
- Per set: weight × reps, reps only, time, or distance + time, depending on the exercise category. Assisted/weighted bodyweight show −kg/+kg.
- "Previous" column with last time's matching set (warm-ups pair with warm-ups). Tap it to copy; blank fields are filled from it when you tick the set.
- Set types: warm-up, drop set, failure. Swipe a set left to delete it.
- Rest timer starts automatically when you complete a set (per-exercise or default rest, off per exercise). ±15 s and skip in-app and from the notification.
- Supersets (rest starts after the last exercise in the round), exercise notes, reorder, replace, remove.
- Live PR detection: a trophy appears on the set the moment you beat a record.
- Ongoing notification, promoted to an Android 16 Live Update, with rest progress, "up next", ±15 s, skip and "Complete set". Vibration and sound when rest is over.
- Keeps the screen on while a workout is open (setting).
- Finishing shows a celebration screen with duration, volume, sets and new records.

**Exercises**
- 155 built-in exercises, named like Strong's ("Bench Press (Barbell)"), plus your own custom ones.
- Search, body-part filters, sort by name, frequency or recency, A–Z headers.
- Exercise page: progress chart (est. 1RM, max weight, volume; reps or time for other categories), full history, records and a rep-records table.

**History & profile**
- Month calendar of workout days and month-grouped workout cards with best sets and PR counts.
- Workout detail with every set and its 1RM; edit, delete, "do it again" or save as template.
- Profile: all-time totals, workouts-per-week chart against your goal, recent records, plate calculator.

**Data**
- Import from Strong: in Strong go to Settings → Export data, then pick the CSV in Stronger's Settings. Both the older comma and newer semicolon formats work. Exercises are matched by name, unknown ones become custom exercises, and records are recalculated. Re-importing skips workouts already present.
- Back up everything to a JSON file and restore it on any phone.
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
             and records (SessionOps), settings (DataStore), Strong CSV import and JSON backup
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
