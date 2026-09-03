#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
if [ "$(uv --version)" != "uv 0.10.11 (006b56b12 2026-03-16)" ]; then
  echo "镜像准备工具版本不匹配" >&2
  exit 78
fi
case "$root" in
  */integrations/mem0-adapter) ;;
  *) echo "镜像准备目录无效" >&2; exit 78 ;;
esac
requirements="$root/build/runtime-requirements.txt"
mkdir -p -- "$root/build"
cd "$root"
uv export --project . --frozen --no-dev --no-emit-project \
  --format requirements-txt --output-file "$requirements"
uv run --frozen python scripts/build_manifest.py
