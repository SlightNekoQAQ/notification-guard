#!/usr/bin/env bash
set -eu
trap 'adb pull /sdcard/Android/data/io.github.slightneko.notificationguard/files/screenshots screenshots || true' EXIT
gradle --no-daemon connectedDebugAndroidTest
