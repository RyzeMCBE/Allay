#!/usr/bin/env bash
# Verify that Allay builds against the exact RyzeMCBE dependency revisions
# recorded by the superproject, not unrelated Maven artifacts or dirty clones.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

verify_submodule() {
  local module="$1"
  local expected_url="$2"
  local configured_url
  configured_url="$(git config -f .gitmodules --get "submodule.${module}.url")"
  if [[ "$configured_url" != "$expected_url" ]]; then
    echo "::error::${module}: expected ${expected_url}, got ${configured_url}" >&2
    exit 1
  fi

  local tree_entry
  tree_entry="$(git ls-tree HEAD "$module")"
  if [[ "$tree_entry" != "160000 commit "* ]]; then
    echo "::error::${module} must be a Git submodule (mode 160000)." >&2
    exit 1
  fi

  local pinned_sha checked_out_sha
  pinned_sha="$(git rev-parse "HEAD:${module}")"
  checked_out_sha="$(git -C "$module" rev-parse HEAD)"
  if [[ "$pinned_sha" != "$checked_out_sha" ]]; then
    echo "::error::${module} is checked out at ${checked_out_sha}, but Allay pins ${pinned_sha}." >&2
    exit 1
  fi

  echo "${module}: ${expected_url} @ ${pinned_sha}"
}

verify_submodule "protocol-local" "https://github.com/RyzeMCBE/Protocol.git"
verify_submodule "stateupdater-local" "https://github.com/RyzeMCBE/StateUpdater.git"
git submodule status --recursive
