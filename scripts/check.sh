#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${ANDROID_HOME:-}" && -f local.properties ]]; then
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' local.properties)"
  if [[ -n "$sdk_dir" ]]; then
    export ANDROID_HOME="$sdk_dir"
    export ANDROID_SDK_ROOT="$sdk_dir"
  fi
fi

./scripts/gradlew-locked \
  :feishu:verifyPlugin \
  :wecom:verifyPlugin \
  :sysu:verifyPlugin \
  :micloud:verifyPlugin \
