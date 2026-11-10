#!/usr/bin/env bash
# Builds the release APK, installs it on the phone and, with --publish, ships a GitHub release.
#
#   scripts/release.sh                      test, build, install on the phone, launch
#   scripts/release.sh --publish            the same, then bump the patch version, commit it, tag,
#                                           push and create the GitHub release with the APK
#   scripts/release.sh --publish --bump minor --notes "Ready-to-progress badges."
#
# Options
#   --publish            bump the version, commit "Release X.Y.Z", tag vX.Y.Z, push, GitHub release.
#                        Needs a clean working tree: commit the work first.
#   --bump KIND          patch (default), minor or major. Only with --publish.
#   --version X.Y.Z      set the version instead of bumping. Only with --publish.
#   --notes TEXT         release notes. Default: commit subjects since the last tag.
#   --notes-file FILE    release notes from a file.
#   --draft              create the GitHub release as a draft.
#   --push               push the current branch without releasing.
#   --no-install         skip the phone.
#   --no-launch          install without opening the app.
#   --no-tests           skip unit tests.
#   --need-device        fail when no phone is reachable (default: warn and carry on).
#   --dry-run            print what --publish would do after the build, without committing or pushing.
#
# The APK is signed by Gradle with the debug key (see app/build.gradle.kts), so it installs over
# the copy on the phone and keeps its data. Gradle runs with the low-memory flags from CLAUDE.md.
# ANDROID_SERIAL picks the phone when several are connected.
set -euo pipefail
cd "$(dirname "$0")/.."

publish=0 bump=patch version="" notes="" notes_file="" draft=0 push=0
install=1 launch=1 tests=1 need_device=0 dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --publish) publish=1 ;;
    --bump) bump="$2"; shift ;;
    --version) version="$2"; shift ;;
    --notes) notes="$2"; shift ;;
    --notes-file) notes_file="$2"; shift ;;
    --draft) draft=1 ;;
    --push) push=1 ;;
    --no-install) install=0 ;;
    --no-launch) launch=0 ;;
    --no-tests) tests=0 ;;
    --need-device) need_device=1 ;;
    --dry-run) dry=1 ;;
    -h|--help) sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

gradle_file=app/build.gradle.kts
pkg=app.stronger
apk=app/build/outputs/apk/release/app-release.apk
old_name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$gradle_file")
old_code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$gradle_file")
new_name=$old_name

# ---- Checks before anything slow ----
if [ $publish = 1 ]; then
  command -v gh >/dev/null || die "gh is not installed"
  [ -z "$(git status --porcelain)" ] || die "working tree is not clean; commit first"
  if [ -z "$version" ]; then
    IFS=. read -r major minor patch <<<"$old_name"
    case "$bump" in
      major) version="$((major + 1)).0.0" ;;
      minor) version="$major.$((minor + 1)).0" ;;
      patch) version="$major.$minor.$((patch + 1))" ;;
      *) die "--bump takes patch, minor or major" ;;
    esac
  fi
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "version must look like 1.2.3"
  git rev-parse -q --verify "refs/tags/v$version" >/dev/null && die "tag v$version already exists"
  new_name=$version
  sed -i "s/versionName = \"$old_name\"/versionName = \"$new_name\"/; s/versionCode = $old_code/versionCode = $((old_code + 1))/" "$gradle_file"
  # Until the bump is committed, any failure puts the old version back.
  trap 'git checkout -q -- "$gradle_file"' EXIT
  say "version $old_name ($old_code) -> $new_name ($((old_code + 1)))"
fi

# ---- Build ----
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
tasks=(assembleRelease)
[ $tests = 1 ] && tasks=(testDebugUnitTest "${tasks[@]}")
say "gradle ${tasks[*]}"
log=$(mktemp)
# R8 needs the 2g heap; everything else is the low-memory setup.
if ! ./gradlew "${tasks[@]}" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g \
    -Pkotlin.compiler.execution.strategy=in-process -q >"$log" 2>&1; then
  grep -v '^w: ' "$log" | tail -40
  rm -f "$log"
  die "build failed"
fi
rm -f "$log"
say "built $apk ($(du -h "$apk" | cut -f1))"

# ---- Phone ----
device() { adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }'; }
connect() {
  [ -n "$(device)" ] && return 0
  # Wireless debugging: already-paired phones announce an ip:port to connect to.
  local target
  target=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ { print $NF; exit }')
  [ -n "$target" ] && adb connect "$target" >/dev/null 2>&1 && sleep 1
  [ -n "$(device)" ]
}
installed="not installed"
if [ $install = 1 ]; then
  if connect; then
    export ANDROID_SERIAL=${ANDROID_SERIAL:-$(device)}
    say "installing on $ANDROID_SERIAL"
    adb install -r "$apk" | tail -1
    installed="installed $(adb shell dumpsys package $pkg | sed -n 's/.*versionName=//p' | head -1)"
    if [ $launch = 1 ]; then
      adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    fi
  elif [ $need_device = 1 ]; then
    die "no phone reachable over adb (pair or reconnect wireless debugging)"
  else
    say "no phone reachable over adb: skipped the install"
    installed="no phone"
  fi
fi

# ---- GitHub ----
branch=$(git rev-parse --abbrev-ref HEAD)
if [ $publish = 1 ]; then
  last_tag=$(git describe --tags --abbrev=0 2>/dev/null || true)
  if [ -n "$notes_file" ]; then
    notes=$(cat "$notes_file")
  elif [ -z "$notes" ]; then
    notes=$(git log --no-merges --pretty='- %s' ${last_tag:+"$last_tag"..HEAD})
  fi
  asset="stronger-$new_name.apk"
  body="$notes

Install \`$asset\` on a phone running Android 13 or newer. The APK is signed with a debug key, so Android or Play Protect may ask you to confirm the install."
  if [ $dry = 1 ]; then
    say "dry run: would commit, tag v$new_name, push $branch and release $asset with:"
    printf '%s\n' "$body"
    exit 0
  fi
  git commit -q -m "Release $new_name" -- "$gradle_file"
  trap - EXIT
  git tag "v$new_name"
  say "pushing $branch and v$new_name"
  git push -q origin "$branch" "v$new_name"
  out=$(mktemp -d)
  cp "$apk" "$out/$asset"
  flags=()
  [ $draft = 1 ] && flags+=(--draft)
  url=$(gh release create "v$new_name" "$out/$asset" --title "Stronger $new_name" --notes "$body" "${flags[@]}")
  rm -rf "$out"
  say "released $url"
elif [ $push = 1 ]; then
  say "pushing $branch"
  git push -q origin "$branch"
fi

say "done: $new_name, $installed"
