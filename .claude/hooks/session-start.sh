#!/bin/bash
# Cloud sessions: JDK 25, the sibling Navigation AI checkout, warm Gradle caches and the soak server template.
#
# 1. JDK 25. Reused without any download when present, in this order:
#      ~/jdk/jdk-25*  ->  $JAVA_HOME if it is Java 25  ->  /usr/lib/jvm/*25*  (the last two get a ~/jdk/jdk-25-* link)
#    Otherwise the first source that works wins (curl --retry with timeouts; the archive is unpacked into a temp
#    dir and moved to ~/jdk/jdk-25* only if bin/java and bin/javac report Java 25):
#      a. Adoptium API  https://api.adoptium.net/v3/binary/latest/25/ga/linux/<arch>/jdk/hotspot/normal/eclipse
#      b. Oracle        https://download.oracle.com/java/25/latest/jdk-25_linux-<arch>_bin.tar.gz
#      c. GitHub        adoptium/temurin25-binaries release asset of the newest GA tag (found with git ls-remote,
#                       because api.github.com and releases/latest are often blocked by the proxy)
#      d. apt-get install -y openjdk-25-jdk-headless   (root or passwordless sudo only)
# 2. ../MinecraftmodNavigationAi: existing dir, ../navigation-ai link, or git clone (3 attempts).
# 3. Gradle warm-up: compile all source sets and resolve testRuntimeClasspath (so a later offline :test works);
#    up to 4 attempts with 20/40/80 s backoff when the log shows 429 or another network error.
# 4. Soak server template (tools/soak/run.py --prepare-only).
#
# Failure behaviour: no `set -e`; every step records ok / WARNING / FAILED and the hook goes on.
#   Steps 3 and 4 are optional: a failure prints a WARNING line and the hook still exits 0.
#   Steps 1 and 2 are essential: what depends on them is skipped with a WARNING, the summary is printed, exit 1.
# Details go to build/session-start.log. Sourcing this file only defines the functions (used to test them).
set -uo pipefail

JDK_ROOT="$HOME/jdk"
JDK=""
LOG=/dev/null
SUMMARY=()
CURL_OPTS=(-fsSL --retry 4 --retry-max-time 300 --connect-timeout 20 --max-time 600 --speed-limit 20000 --speed-time 60)
TRANSIENT='429|Too Many Requests|Could not (download|GET|HEAD|resolve)|Connection (reset|refused|timed out)|Read timed out|Remote host terminated|status code 5[0-9][0-9]|Premature end|UnknownHost|No route to host'

warn() { echo "WARNING: session-start: $*" >&2; }
note() { SUMMARY+=("$(printf '%-8s %s' "$1" "$2")"); }

java_major() {  # major Java version of the JDK at $1 (release file first, no JVM start)
  local home="$1" v=""
  [ -x "$home/bin/java" ] && [ -x "$home/bin/javac" ] || return 1
  [ -f "$home/release" ] && v="$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$home/release")"
  [ -n "$v" ] || v="$("$home/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n1)"
  [ -n "$v" ] && echo "$v"
}
is_jdk25() { [ "$(java_major "$1" 2>/dev/null)" = 25 ]; }

find_jdk25() {  # prints an installed Java 25 JDK; never downloads anything
  local d
  while IFS= read -r d; do
    is_jdk25 "$d" && { echo "$d"; return 0; }
  done < <(printf '%s\n' "$JDK_ROOT"/jdk-25* | sort -rV)
  if [ -n "${JAVA_HOME:-}" ] && is_jdk25 "$JAVA_HOME"; then echo "$JAVA_HOME"; return 0; fi
  while IFS= read -r d; do
    is_jdk25 "$d" && { echo "$d"; return 0; }
  done < <(printf '%s\n' /usr/lib/jvm/*25* | sort -rV)
  return 1
}

link_into_jdk_root() {  # tools/test scripts look for ~/jdk/jdk-25*
  case "$1" in "$JDK_ROOT"/jdk-25*) return 0 ;; esac
  mkdir -p "$JDK_ROOT" && ln -sfn "$1" "$JDK_ROOT/jdk-25-$(basename "$1")"
}

jdk_arch() { case "$(uname -m)" in aarch64|arm64) echo aarch64 ;; *) echo x64 ;; esac; }

jdk_urls() {  # archive URLs of one source, best first
  local arch tag ver
  arch="$(jdk_arch)"
  case "$1" in
    adoptium) echo "https://api.adoptium.net/v3/binary/latest/25/ga/linux/$arch/jdk/hotspot/normal/eclipse" ;;
    oracle) echo "https://download.oracle.com/java/25/latest/jdk-25_linux-${arch}_bin.tar.gz" ;;
    github)
      timeout 60 git ls-remote --tags --refs https://github.com/adoptium/temurin25-binaries 2>/dev/null \
        | grep -oE 'jdk-25(\.[0-9]+)*\+[0-9]+$' | sort -rV | head -n 2 \
        | while IFS= read -r tag; do
            ver="${tag#jdk-}"
            echo "https://github.com/adoptium/temurin25-binaries/releases/download/${tag/+/%2B}/OpenJDK25U-jdk_${arch}_linux_hotspot_${ver/+/_}.tar.gz"
          done ;;
  esac
}

install_jdk_archive() {  # unpacks $1 next to ~/jdk, keeps it only if it is a Java 25 JDK; prints the new dir
  local tmp top name
  tmp="$(mktemp -d "$JDK_ROOT/.extract.XXXXXX")" || return 1
  if ! tar -xzf "$1" -C "$tmp" 2>>"$LOG"; then rm -rf "$tmp"; return 1; fi
  top="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n1)"
  if [ -z "$top" ] || ! is_jdk25 "$top"; then rm -rf "$tmp"; return 1; fi
  name="$(basename "$top")"
  case "$name" in jdk-25*) ;; *) name="jdk-25-$name" ;; esac
  if [ -e "$JDK_ROOT/$name" ] || [ -L "$JDK_ROOT/$name" ]; then mv "$JDK_ROOT/$name" "$JDK_ROOT/.broken-$name-$$"; fi
  mv "$top" "$JDK_ROOT/$name" && rm -rf "$tmp" && echo "$JDK_ROOT/$name"
}

apt_install_jdk25() {
  local -a sudo=()
  command -v apt-get >/dev/null 2>&1 || return 1
  if [ "$(id -u)" != 0 ]; then sudo -n true 2>/dev/null || return 1; sudo=(sudo -n); fi
  "${sudo[@]}" env DEBIAN_FRONTEND=noninteractive timeout 600 apt-get install -y -q openjdk-25-jdk-headless >>"$LOG" 2>&1 && return 0
  "${sudo[@]}" timeout 300 apt-get update -q >>"$LOG" 2>&1 \
    && "${sudo[@]}" env DEBIAN_FRONTEND=noninteractive timeout 600 apt-get install -y -q openjdk-25-jdk-headless >>"$LOG" 2>&1
}

step_jdk() {  # sets JDK; returns 1 when no source gave a Java 25 JDK
  local found src url archive
  if found="$(find_jdk25)"; then
    link_into_jdk_root "$found" || warn "could not link $found into $JDK_ROOT"
    JDK="$found"; note ok "JDK 25: reused $found"; return 0
  fi
  mkdir -p "$JDK_ROOT" || { note FAILED "JDK 25: cannot create $JDK_ROOT"; return 1; }
  rm -rf "$JDK_ROOT"/.extract.*  # leftovers of an interrupted earlier run of this hook
  archive="$JDK_ROOT/.jdk25-download.tar.gz"
  for src in adoptium oracle github; do
    while IFS= read -r url; do
      echo "session-start: JDK 25 from $src: $url" >&2
      if curl "${CURL_OPTS[@]}" -o "$archive" "$url" </dev/null && found="$(install_jdk_archive "$archive")"; then
        rm -f "$archive"; JDK="$found"; note ok "JDK 25: installed $found from $src"; return 0
      fi
      rm -f "$archive"
    done < <(jdk_urls "$src")
    warn "JDK 25 source '$src' failed (blocked host, no release or bad archive), trying the next one"
  done
  if apt_install_jdk25 && found="$(find_jdk25)"; then
    link_into_jdk_root "$found" || warn "could not link $found into $JDK_ROOT"
    JDK="$found"; note ok "JDK 25: apt openjdk-25-jdk-headless ($found)"; return 0
  fi
  warn "JDK 25 source 'apt' failed; no JDK 25 available"
  note FAILED "JDK 25: Adoptium, Oracle, GitHub and apt all failed (see stderr and $LOG)"
  return 1
}

step_nav() {
  local attempt
  if [ -e "$NAV" ]; then note ok "Navigation AI: $NAV"; return 0; fi
  if [ -d "$PARENT/navigation-ai/.git" ] && ln -s "$PARENT/navigation-ai" "$NAV"; then
    note ok "Navigation AI: linked $PARENT/navigation-ai"; return 0
  fi
  for attempt in 1 2 3; do
    if timeout 300 git clone --depth 1 https://github.com/Arthur32132/navigation-ai "$NAV" >>"$LOG" 2>&1; then
      note ok "Navigation AI: cloned into $NAV"; return 0
    fi
    [ "$attempt" = 3 ] || sleep $((attempt * 10))
  done
  warn "git clone of navigation-ai failed 3 times (see $LOG)"
  note FAILED "Navigation AI: missing $NAV, clone failed"
  return 1
}

step_warmup() {
  local attempt init rc=1 start delay
  init="$(mktemp --suffix=.gradle)" || return 1
  cat >"$init" <<'GRADLE'
allprojects {
    plugins.withId('java') {
        tasks.register('avWarmTestRuntime') {
            def classpath = configurations.testRuntimeClasspath
            inputs.files(classpath)
            doLast { classpath.files }
        }
    }
}
GRADLE
  for attempt in 1 2 3 4; do
    start=$(($(wc -l <"$LOG") + 1))
    echo "== gradle warm-up attempt $attempt" >>"$LOG"
    (cd "$PROJECT" && timeout 1200 sh ./gradlew --no-daemon -q --console=plain --continue -I "$init" compileJava compileTestJava soakClasses \
      :navigationAi:compileJava :navigationAi:compileTestJava avWarmTestRuntime) >>"$LOG" 2>&1
    rc=$?
    [ "$rc" = 0 ] && break
    if [ "$attempt" = 4 ] || ! tail -n "+$start" "$LOG" | grep -Eqi "$TRANSIENT"; then break; fi
    delay=$((20 << (attempt - 1)))
    warn "Gradle warm-up attempt $attempt hit a network error (Maven Central 429?), retrying in ${delay}s"
    sleep "$delay"
  done
  rm -f "$init"
  return "$rc"
}

step_soak() {
  command -v python3 >/dev/null 2>&1 || { echo "python3 not found" >>"$LOG"; return 1; }
  (cd "$PROJECT" && AV_JAVA_HOME="$JDK" timeout 900 python3 tools/soak/run.py --prepare-only) >>"$LOG" 2>&1
}

main() {
  if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
  fi
  local failed=0
  PROJECT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
  PARENT="$(dirname "$PROJECT")"
  NAV="$PARENT/MinecraftmodNavigationAi"
  if mkdir -p "$PROJECT/build" 2>/dev/null && : >"$PROJECT/build/session-start.log"; then
    LOG="$PROJECT/build/session-start.log"
  else
    LOG="$(mktemp)"
  fi

  # 1. Minecraft 26.2 needs Java 25; the image ships an older JDK.
  if step_jdk; then
    export JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH"
    if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
      echo "export JAVA_HOME=\"$JDK\"" >> "$CLAUDE_ENV_FILE"
      echo "export AV_JAVA_HOME=\"$JDK\"" >> "$CLAUDE_ENV_FILE"
      echo "export PATH=\"$JDK/bin:\$PATH\"" >> "$CLAUDE_ENV_FILE"
    fi
  else
    failed=1
  fi

  # 2. settings.gradle expects ../MinecraftmodNavigationAi next to this project.
  step_nav || failed=1

  # 3. Warm the Gradle and Loom caches, test runtime classpath included (Maven Central sometimes answers 429; retry).
  if [ -z "$JDK" ] || [ ! -e "$NAV" ]; then
    warn "Gradle warm-up skipped: no JDK 25 or no Navigation AI checkout"
    note WARNING "Gradle warm-up: skipped (no JDK 25 or Navigation AI)"
  elif step_warmup; then
    note ok "Gradle warm-up: compile + test runtime classpath cached"
  else
    warn "Gradle warm-up failed; builds will download on first use (see $LOG)"
    note WARNING "Gradle warm-up: failed (see $LOG)"
  fi

  # 4. Cached headless Fabric server for tools/soak/run.py.
  if [ -z "$JDK" ]; then
    warn "soak template skipped: no JDK 25"
    note WARNING "soak template: skipped (no JDK 25)"
  elif step_soak; then
    note ok "soak template: ready"
  else
    warn "soak template preparation failed; tools/soak/run.py will retry it (see $LOG)"
    note WARNING "soak template: failed (see $LOG)"
  fi

  echo "session-start summary:"
  printf '  %s\n' "${SUMMARY[@]}"
  exit "$failed"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
