#!/bin/bash
# SessionStart 훅 — 세션마다 git 작업 환경을 자동 정렬.
# 목적: (1) pre-push QA 레칫 자동 활성화, (2) 원격(Claude Code on the web)
#       세션의 커밋 identity 를 Claude 로 고정 → "Unverified 커밋" 경고 제거.
set -euo pipefail

# (1) pre-push 레칫 게이트 활성화 (로컬/원격 공통, 멱등).
git config core.hooksPath scripts/hooks 2>/dev/null || true

# (2) 원격 세션에서만 identity 고정. 로컬 PC 세션은 사용자 본인 identity 유지.
if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
  git config user.email "noreply@anthropic.com"
  git config user.name "Claude"
fi

exit 0
