#!/usr/bin/env sh
set -eu

version="${1:?usage: release-notes.sh VERSION}"

awk -v version="$version" '
  $0 ~ "^## " version "([[:space:]]|$)" { found = 1; next }
  found && /^## / { exit }
  found { print }
  END { if (!found) exit 1 }
' CHANGELOG.md
