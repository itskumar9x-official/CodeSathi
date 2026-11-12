#!/bin/sh
# Minimal Gradle wrapper entry point for CodeSathi.
# On a machine with Android Studio / Gradle installed, prefer the standard
# wrapper (regenerate with `gradle wrapper --gradle-version 8.2`).
# This script only forwards to a `gradle` on PATH.
exec gradle "$@"
