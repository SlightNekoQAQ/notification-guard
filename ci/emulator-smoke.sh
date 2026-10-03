#!/usr/bin/env bash
set -eu
trap 'adb pull /sdcard/Pictures/NotificationGuardSmoke screenshots || true' EXIT
gradle --no-daemon connectedDebugAndroidTest
