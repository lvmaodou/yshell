#!/bin/sh
set -eu

PLAN_DIR="$1"
PID_TO_WAIT="$2"

read_prop() {
  grep -E "^$1=" "$PLAN_DIR/update-plan.properties" | sed "s/^$1=//" | tail -n 1
}

join_path() {
  root="$1"
  rel=$(printf "%s" "$2" | sed 's#/#/#g')
  printf "%s/%s" "$root" "$rel"
}

while kill -0 "$PID_TO_WAIT" 2>/dev/null; do
  sleep 1
done
sleep 1

INSTALL_DIR=$(read_prop installDir)
LAUNCHER_PATH=$(read_prop launcherPath)
STAGING_DIR=$(read_prop stagingDir)

COPY_LIST="$PLAN_DIR/copy-files.tsv"
if [ -f "$COPY_LIST" ]; then
  while IFS="$(printf '\t')" read -r source_rel target_rel; do
    [ -n "$source_rel" ] || continue
    source=$(join_path "$STAGING_DIR" "$source_rel")
    target=$(join_path "$INSTALL_DIR" "$target_rel")
    mkdir -p "$(dirname "$target")"
    cp -f "$source" "$target"
  done < "$COPY_LIST"
fi

DELETE_LIST="$PLAN_DIR/delete-files.txt"
if [ -f "$DELETE_LIST" ]; then
  while IFS= read -r target_rel; do
    [ -n "$target_rel" ] || continue
    rm -f "$(join_path "$INSTALL_DIR" "$target_rel")"
  done < "$DELETE_LIST"
fi

if [ "$(read_prop runtimeUpdate)" = "true" ]; then
  archive="$STAGING_DIR/$(read_prop runtimeArchive)"
  extract_dir="$PLAN_DIR/runtime-extracted"
  rm -rf "$extract_dir"
  mkdir -p "$extract_dir"

  case "$archive" in
    *.zip) unzip -q "$archive" -d "$extract_dir" ;;
    *.tar.gz|*.tgz) tar -xzf "$archive" -C "$extract_dir" ;;
    *) echo "Unsupported runtime archive: $archive" >&2; exit 1 ;;
  esac

  source_runtime="$extract_dir"
  child_count=$(find "$extract_dir" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')
  if [ "$child_count" = "1" ]; then
    only_child=$(find "$extract_dir" -mindepth 1 -maxdepth 1 | head -n 1)
    if [ -d "$only_child" ]; then
      source_runtime="$only_child"
    fi
  fi

  runtime_path="$INSTALL_DIR/runtime"
  backup_path="$runtime_path.backup"
  rm -rf "$backup_path"
  if [ -e "$runtime_path" ]; then
    mv "$runtime_path" "$backup_path"
  fi
  mv "$source_runtime" "$runtime_path"
  rm -rf "$backup_path"
fi

if [ "$(read_prop relaunch)" != "false" ]; then
  if [ -x "$LAUNCHER_PATH" ]; then
    (cd "$INSTALL_DIR" && "$LAUNCHER_PATH" >/dev/null 2>&1 &)
  elif [ -f "$LAUNCHER_PATH" ]; then
    chmod +x "$LAUNCHER_PATH" || true
    (cd "$INSTALL_DIR" && "$LAUNCHER_PATH" >/dev/null 2>&1 &)
  fi
fi

sleep 2
rm -rf "$PLAN_DIR"
