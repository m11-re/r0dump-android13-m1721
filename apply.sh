#!/usr/bin/env bash
#
# r0dump for Android 13 (LineageOS 20) — 一键集成脚本
#
# 用法:
#   ./apply.sh /path/to/lineageos-20-source          # 应用全部
#   ./apply.sh /path/to/src --check                  # 只检查能否干净应用
#   ./apply.sh /path/to/src --reverse                # 反向撤销
#   ./apply.sh /path/to/src --only art,libcore       # 只应用指定项目
#
# 前提: 目标源码树已 repo sync 到 andoid-13.0.0_r75 / lineage-20 基线。
#       补丁基线见 patches/baselines.txt（各项目 HEAD 需匹配，否则可能需 -3 三方合并）。
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${1:-}"
shift || true
MODE="apply"
ONLY=""

while [ $# -gt 0 ]; do
  case "$1" in
    --check)   MODE="check" ;;
    --reverse) MODE="reverse" ;;
    --only)    ONLY="$2"; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
  shift
done

if [ -z "$SRC" ]; then
  echo "用法: $0 <lineageos-20 源码根目录> [--check|--reverse|--only proj1,proj2]" >&2
  exit 2
fi
if [ ! -d "$SRC/.repo" ]; then
  echo "错误: $SRC 看起来不是 repo 管理的 LineageOS 源码树(缺 .repo)" >&2
  exit 1
fi

# 项目 -> 补丁
PROJECTS=(
  "art:01-art.patch"
  "frameworks/base:02-frameworks-base.patch"
  "libcore:03-libcore.patch"
  "system/sepolicy:04-system-sepolicy.patch"
  "device/meizu/m1721:05-device-meizu-m1721.patch"
)

want() {
  [ -z "$ONLY" ] && return 0
  case ",$ONLY," in *",$1,"*) return 0 ;; *) return 1 ;; esac
}

fail=0
for entry in "${PROJECTS[@]}"; do
  proj="${entry%%:*}"; patch="${entry##*:}"
  want "$proj" || continue

  dir="$SRC/$proj"
  pf="$HERE/patches/$patch"

  if [ ! -d "$dir" ]; then
    echo "[跳过] $proj 不存在"
    continue
  fi
  if ! git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
    echo "[跳过] $proj 不是 git 仓库"
    continue
  fi

  git -C "$dir" add -N . >/dev/null 2>&1 || true

  case "$MODE" in
    check)
      if git -C "$dir" apply --check "$pf" 2>/dev/null; then
        echo "[OK]    $proj  <- $patch"
      elif git -C "$dir" apply --check -R "$pf" 2>/dev/null; then
        echo "[已应用] $proj  (反向可应用，说明补丁已在树内)"
      else
        echo "[冲突]  $proj  <- $patch"
        fail=1
      fi
      ;;
    reverse)
      if git -C "$dir" apply -R "$pf" 2>/dev/null; then
        echo "[撤销]  $proj"
      else
        echo "[跳过]  $proj 无法反向应用(可能未打或已改动)"
      fi
      ;;
    apply)
      if git -C "$dir" apply --check -R "$pf" 2>/dev/null; then
        echo "[已在]  $proj"
      elif git -C "$dir" apply "$pf" 2>/dev/null; then
        echo "[应用]  $proj  <- $patch"
      elif git -C "$dir" apply -3 "$pf" 2>/dev/null; then
        echo "[三方]  $proj  <- $patch  (基线不同，已 -3 合并，注意检查)"
        fail=1
      else
        echo "[失败]  $proj  <- $patch"
        fail=1
      fi
      ;;
  esac
done

# tree/ 里的整目录直接拷（当前只有 R0dumpManager）
if want "packages/apps/R0dumpManager" && [ -d "$HERE/tree" ]; then
  if [ -d "$SRC/packages/apps" ]; then
    if [ "$MODE" = "apply" ]; then
      cp -a "$HERE/tree/packages/apps/R0dumpManager" "$SRC/packages/apps/"
      echo "[拷贝]  packages/apps/R0dumpManager"
    elif [ "$MODE" = "check" ]; then
      [ -d "$SRC/packages/apps/R0dumpManager" ] \
        && echo "[已在]  packages/apps/R0dumpManager" \
        || echo "[待拷]  packages/apps/R0dumpManager"
    fi
  fi
fi

echo
if [ "$MODE" = "check" ]; then
  [ $fail -eq 0 ] && echo "全部补丁可干净应用。" || echo "存在冲突，请检查上面标记。"
else
  [ $fail -eq 0 ] && echo "完成。" || echo "部分步骤需人工确认。"
fi
exit $fail
