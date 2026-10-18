#!/bin/bash
# Cloud sessions: JDK 25, the sibling Navigation AI checkout, warm Gradle caches and the soak server template.
set -euo pipefail
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
PARENT="$(dirname "$PROJECT")"
JDK_ROOT="$HOME/jdk"

# 1. Minecraft 26.2 needs Java 25; the image ships an older JDK.
JDK="$(ls -d "$JDK_ROOT"/jdk-25* 2>/dev/null | head -n1 || true)"
if [ -z "$JDK" ]; then
  mkdir -p "$JDK_ROOT"
  curl -fsSL --retry 4 -o "$JDK_ROOT/jdk25.tar.gz" "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar -xzf "$JDK_ROOT/jdk25.tar.gz" -C "$JDK_ROOT"
  rm -f "$JDK_ROOT/jdk25.tar.gz"
  JDK="$(ls -d "$JDK_ROOT"/jdk-25* | head -n1)"
fi
export JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH"
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export JAVA_HOME=\"$JDK\"" >> "$CLAUDE_ENV_FILE"
  echo "export AV_JAVA_HOME=\"$JDK\"" >> "$CLAUDE_ENV_FILE"
  echo "export PATH=\"$JDK/bin:\$PATH\"" >> "$CLAUDE_ENV_FILE"
fi

# 2. settings.gradle expects ../MinecraftmodNavigationAi next to this project.
NAV="$PARENT/MinecraftmodNavigationAi"
if [ ! -e "$NAV" ]; then
  if [ -d "$PARENT/navigation-ai/.git" ]; then
    ln -s "$PARENT/navigation-ai" "$NAV"
  else
    git clone --depth 1 https://github.com/Arthur32132/navigation-ai "$NAV"
  fi
fi

# 3. Warm the Gradle and Loom caches (Maven Central sometimes answers 429; retry).
cd "$PROJECT"
for attempt in 1 2 3 4; do
  if sh ./gradlew --no-daemon -q --console=plain compileJava compileTestJava soakClasses :navigationAi:compileJava :navigationAi:compileTestJava; then
    break
  fi
  if [ "$attempt" = 4 ]; then echo "gradle warm-up failed" >&2; exit 1; fi
  sleep $((attempt * 15))
done

# 4. Cached headless Fabric server for tools/soak/run.py.
AV_JAVA_HOME="$JDK" python3 tools/soak/run.py --prepare-only
