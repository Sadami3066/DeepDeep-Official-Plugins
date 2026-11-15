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
  :cli-common:testDebugUnitTest \
  :feishu:testDebugUnitTest \
  :feishu:verifyPlugin \
  :wecom:testDebugUnitTest \
  :wecom:verifyPlugin \
  :sysu:testDebugUnitTest \
  :sysu:verifyPlugin \
  :wechat:testDebugUnitTest \
  :wechat:verifyPlugin \
  :micloud:testDebugUnitTest \
  :micloud:verifyPlugin \
  :mail:testDebugUnitTest \
  :mail:verifyPlugin \
  :themes:testDebugUnitTest \
  :themes:verifyPlugin \
  :rail:testDebugUnitTest \
  :rail:verifyPlugin \
  :hotel:testDebugUnitTest \
  :hotel:verifyPlugin \
  :ride:testDebugUnitTest \
  :ride:verifyPlugin \
  :amap:testDebugUnitTest \
  :amap:verifyPlugin
