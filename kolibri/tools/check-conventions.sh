#!/usr/bin/env bash
# Kolibri entry point. The convention linter lives ONCE in tools/check-conventions.sh
# (SPEC_NYX_REWRITE A3); kolibri's scan roots, positive lists and per-check decisions
# are data in tools/conventions/kolibri.conf. Kept so `./gradlew :kolibri:app:checkConventions`
# and existing habits keep working — add checks in tools/, never here.
exec bash "$(cd "$(dirname "$0")/../.." && pwd)/tools/check-conventions.sh" --app kolibri "$@"
