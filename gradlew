#!/bin/sh
# Gradle Start Up Script for UN*X
if command -v gradle >/dev/null 2>&1 && [ ! -f "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec gradle "$@"
fi
exec java -jar "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" "$@"
