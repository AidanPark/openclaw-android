#!/usr/bin/env bash
# backup.sh — oa --backup / oa --restore implementation
# Sourced by oa.sh after lib.sh is loaded.

# ── Constants ──
BACKUP_DIR="$PROJECT_DIR/backup"
BACKUP_SCHEMA_VERSION=1

# ── Helpers ──

# Build an ISO-8601 timestamp matching OpenClaw's naming rule:
# colons replaced with dashes, e.g. 2026-03-14T00-00-00.000Z
_backup_timestamp() {
    date -u +"%Y-%m-%dT%H-%M-%S.000Z"
}

# Return the archive-root name embedded inside the tarball.
# OpenClaw uses the bare filename (without .tar.gz) as archiveRoot.
_backup_archive_root() {
    local basename="$1"          # e.g. 2026-…-openclaw-backup
    echo "$basename"
}

# Collect all paths that exist under a given data dir and return them
# as a bash array (by name).  Skips missing paths silently.
# Usage: _collect_assets <data_dir> <array_name>
_collect_assets() {
    local data_dir="$1"
    local -n _arr="$2"           # nameref — bash 4.3+

    local candidates=(
        "openclaw.json5"
        "openclaw.json"
        ".env"
        "secrets.json"
        "credentials"
        "identity"
        "auth"
        "sessions"
        "workspace"
    )

    # Agents directory — dynamic enumeration
    if [ -d "$data_dir/agents" ]; then
        while IFS= read -r agent_path; do
            local rel="${agent_path#"$data_dir/"}"
            candidates+=("$rel")
        done < <(find "$data_dir/agents" -mindepth 1 -maxdepth 2 -name "agent" 2>/dev/null)
    fi

    _arr=()
    for rel in "${candidates[@]}"; do
        local full="$data_dir/$rel"
        if [ -e "$full" ]; then
            _arr+=("$rel")
        fi
    done
}

# Detect which runtime owns a backup by inspecting its manifest.json.
# Echoes the platform name (e.g. "openclaw"), or "" on failure.
_detect_backup_platform() {
    local archive="$1"

    # Extract manifest.json from the tarball without unpacking everything
    local manifest
    manifest=$(gzip -dc "$archive" 2>/dev/null | tar -xf - --wildcards "*/manifest.json" -O 2>/dev/null | head -c 65536)

    if [ -z "$manifest" ]; then
        echo ""
        return 1
    fi

    # Quick heuristic: look for known platform fingerprints in sourcePath values
    if echo "$manifest" | grep -q '"\.openclaw"'; then
        echo "openclaw"
        return 0
    fi
    if echo "$manifest" | grep -q '\.openclaw'; then
        echo "openclaw"
        return 0
    fi

    echo ""
    return 1
}

# Return the restore root directory for a given platform name.
_restore_root_for_platform() {
    local platform="$1"
    case "$platform" in
        openclaw)
            echo "$HOME/.openclaw"
            ;;
        *)
            # Future platforms: extend here
            echo ""
            ;;
    esac
}

# ── cmd_backup ──────────────────────────────────────────────────────────────

cmd_backup() {
    if ! command -v gzip &>/dev/null; then
        echo "  Installing gzip..."
        pkg_safe install -y gzip 2>/dev/null
    fi

    local output_dir="${1:-}"

    # Resolve output directory
    if [ -z "$output_dir" ]; then
        output_dir="$BACKUP_DIR"
    fi

    echo ""
    echo -e "${BOLD}OpenClaw on Android — Backup${NC}"
    echo -e "────────────────────────────────────────"

    # Load platform config to get PLATFORM_DATA_DIR
    local platform
    platform=$(detect_platform 2>/dev/null) || platform=""

    if [ -z "$platform" ]; then
        echo -e "${RED}[FAIL]${NC} Could not detect installed platform."
        exit 1
    fi

    load_platform_config "$platform" "$PROJECT_DIR" 2>/dev/null || {
        echo -e "${RED}[FAIL]${NC} Could not load platform config for: $platform"
        exit 1
    }

    local data_dir="$PLATFORM_DATA_DIR"

    if [ ! -d "$data_dir" ]; then
        echo -e "${RED}[FAIL]${NC} Platform data directory not found: $data_dir"
        exit 1
    fi

    # Collect assets
    local assets=()
    _collect_assets "$data_dir" assets

    if [ ${#assets[@]} -eq 0 ]; then
        echo -e "${YELLOW}[WARN]${NC} No backup targets found in $data_dir"
        exit 1
    fi

    echo -e "  Platform:    $platform"
    echo -e "  Source:      $data_dir"
    echo -e "  Destination: $output_dir"
    echo -e "  Assets:      ${#assets[@]} item(s)"
    echo ""

    # Create output directory
    mkdir -p "$output_dir"

    # Build filename — OpenClaw naming rule
    local ts
    ts=$(_backup_timestamp)
    local basename="${ts}-openclaw-backup"
    local archive_filename="${basename}.tar.gz"
    local archive_path="$output_dir/$archive_filename"
    local archive_root
    archive_root=$(_backup_archive_root "$basename")

    # Build manifest.json in a temp dir, then pack everything
    local tmpdir
    tmpdir=$(mktemp -d "${TMPDIR:-/tmp}/oa-backup.XXXXXX")
    trap 'rm -rf "'"$tmpdir"'"' EXIT

    local staging="$tmpdir/$archive_root"
    local payload_dir="$staging/payload"
    mkdir -p "$payload_dir"

    # Copy each asset into payload/, preserving relative structure
    echo -e "Collecting files…"
    local manifest_assets_json=""
    local sep=""
    for rel in "${assets[@]}"; do
        local src="$data_dir/$rel"
        local dst="$payload_dir/$rel"

        # Determine kind
        local kind="state"
        case "$rel" in
            openclaw.json5|openclaw.json) kind="config" ;;
            .env|secrets.json|credentials|identity|auth) kind="config" ;;
            workspace*) kind="workspace" ;;
            agents*) kind="workspace" ;;
            sessions*) kind="state" ;;
        esac

        local archive_path_rel="$archive_root/payload/$rel"

        if [ -d "$src" ]; then
            mkdir -p "$dst"
            cp -a "$src/." "$dst/"
        else
            mkdir -p "$(dirname "$dst")"
            cp -a "$src" "$dst"
        fi

        # Append to manifest assets JSON array
        manifest_assets_json+="${sep}"
        manifest_assets_json+=$(printf '    {\n      "kind": "%s",\n      "sourcePath": "%s",\n      "archivePath": "%s"\n    }' \
            "$kind" "$src" "$archive_path_rel")
        sep=$',\n'
    done

    # Generate manifest.json
    local node_version runtime_version
    node_version=$(node --version 2>/dev/null || echo "unknown")
    runtime_version=$(openclaw --version 2>/dev/null | head -1 || echo "unknown")

    cat > "$staging/manifest.json" <<MANIFEST_EOF
{
  "schemaVersion": $BACKUP_SCHEMA_VERSION,
  "createdAt": "$(date -u +"%Y-%m-%dT%H:%M:%S.000Z")",
  "archiveRoot": "$archive_root",
  "runtimeVersion": "$runtime_version",
  "platform": "linux",
  "nodeVersion": "$node_version",
  "assets": [
$manifest_assets_json
  ]
}
MANIFEST_EOF

    # Pack the archive using tar + gzip pipe (Android safe)
    # Piping through gzip explicitly ensures the shell resolves gzip via PATH,
    # avoiding "gzip: Cannot exec" errors when tar can't find gzip internally.
    echo -e "Packing archive…"
    if ! tar -cf - -C "$tmpdir" "$archive_root" | gzip > "$archive_path"; then
        echo -e "${RED}[FAIL]${NC} Failed to create archive: $archive_path"
        exit 1
    fi

    echo -e "${GREEN}[OK]${NC}   Archive created: $archive_path"
    echo ""

    # ── Integrity verification ──
    echo -e "Verifying integrity…"

    # Try openclaw backup verify first (preferred — full manifest check)
    if command -v openclaw &>/dev/null && openclaw backup verify "$archive_path" &>/dev/null 2>&1; then
        echo -e "${GREEN}[OK]${NC}   Integrity check passed (openclaw backup verify)"
    else
        # Fallback: tar -tzf structural check
        local file_count
        file_count=$(gzip -dc "$archive_path" 2>/dev/null | tar -tf - 2>/dev/null | wc -l)
        if [ "$file_count" -gt 0 ]; then
            echo -e "${GREEN}[OK]${NC}   Integrity check passed (tar structural, $file_count entries)"
        else
            echo -e "${RED}[FAIL]${NC} Integrity check failed — archive may be corrupt"
            exit 1
        fi
    fi

    echo ""
    echo -e "${GREEN}Backup complete.${NC}"
    echo -e "  File: $archive_path"
    echo -e "  Size: $(du -sh "$archive_path" | cut -f1)"
    echo ""
}

# ── cmd_restore ─────────────────────────────────────────────────────────────

cmd_restore() {
    if ! command -v gzip &>/dev/null; then
        echo "  Installing gzip..."
        pkg install -y gzip 2>/dev/null || { echo -e "${RED}[FAIL]${NC} gzip not found and could not be installed"; exit 1; }
    fi

    echo ""
    echo -e "${BOLD}OpenClaw on Android — Restore${NC}"
    echo -e "────────────────────────────────────────"

    # Collect backup files
    if [ ! -d "$BACKUP_DIR" ]; then
        echo -e "${RED}[FAIL]${NC} Backup directory not found: $BACKUP_DIR"
        echo -e "       Run ${BOLD}oa --backup${NC} first."
        exit 1
    fi

    local -a backups=()
    while IFS= read -r f; do
        backups+=("$f")
    done < <(ls -t "$BACKUP_DIR"/*.tar.gz 2>/dev/null)

    if [ ${#backups[@]} -eq 0 ]; then
        echo -e "${RED}[FAIL]${NC} No backup files found in $BACKUP_DIR"
        echo -e "       Run ${BOLD}oa --backup${NC} first."
        exit 1
    fi

    # Display numbered list
    echo -e "Available backups:"
    echo ""
    local idx=1
    for f in "${backups[@]}"; do
        local fname size
        fname=$(basename "$f")
        size=$(du -sh "$f" 2>/dev/null | cut -f1)
        printf "  ${BOLD}[%d]${NC} %s  ${YELLOW}(%s)${NC}\n" "$idx" "$fname" "$size"
        idx=$((idx + 1))
    done

    echo ""
    local choice
    if (echo -n "" > /dev/tty) 2>/dev/null; then
        read -rp "Select backup to restore [1-${#backups[@]}]: " choice < /dev/tty
    else
        read -rp "Select backup to restore [1-${#backups[@]}]: " choice
    fi

    # Validate input
    if ! [[ "$choice" =~ ^[0-9]+$ ]] || [ "$choice" -lt 1 ] || [ "$choice" -gt "${#backups[@]}" ]; then
        echo -e "${RED}[FAIL]${NC} Invalid selection: $choice"
        exit 1
    fi

    local selected="${backups[$((choice - 1))]}"
    echo ""
    echo -e "  Selected: ${BOLD}$(basename "$selected")${NC}"

    # Detect platform from manifest
    echo -e "  Detecting platform…"
    local platform
    platform=$(_detect_backup_platform "$selected")

    if [ -z "$platform" ]; then
        echo -e "${RED}[FAIL]${NC} Could not determine backup platform from manifest."
        exit 1
    fi

    local restore_root
    restore_root=$(_restore_root_for_platform "$platform")

    if [ -z "$restore_root" ]; then
        echo -e "${RED}[FAIL]${NC} Unsupported platform in backup: $platform"
        exit 1
    fi

    echo -e "  Platform:    $platform"
    echo -e "  Restore to:  $restore_root"
    echo ""

    # ── Warning ──
    echo -e "${YELLOW}┌─────────────────────────────────────────────────┐${NC}"
    echo -e "${YELLOW}│  WARNING: This will overwrite your current       │${NC}"
    echo -e "${YELLOW}│  configuration and data in:                      │${NC}"
    echo -e "${YELLOW}│                                                   │${NC}"
    echo -e "${YELLOW}│    $restore_root${NC}"
    echo -e "${YELLOW}│                                                   │${NC}"
    echo -e "${YELLOW}│  Existing files will be replaced. This cannot    │${NC}"
    echo -e "${YELLOW}│  be undone unless you have another backup.       │${NC}"
    echo -e "${YELLOW}└─────────────────────────────────────────────────┘${NC}"
    echo ""

    if ! ask_yn "Continue with restore?"; then
        echo -e "Restore cancelled."
        exit 0
    fi

    echo ""
    echo -e "Restoring…"

    # Extract archive: only the payload/ contents go to restore_root
    # The archive structure is: <archiveRoot>/payload/<rel_path>
    # We strip the first two components (<archiveRoot>/payload) and restore to restore_root

    # Get archiveRoot from manifest
    local archive_root
    archive_root=$(gzip -dc "$selected" 2>/dev/null | tar -xf - --wildcards "*/manifest.json" -O 2>/dev/null \
        | grep '"archiveRoot"' \
        | sed 's/.*"archiveRoot"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')

    if [ -z "$archive_root" ]; then
        echo -e "${RED}[FAIL]${NC} Could not read archiveRoot from manifest."
        exit 1
    fi

    mkdir -p "$restore_root"

    # Extract payload files, stripping <archiveRoot>/payload/ prefix
    if ! gzip -dc "$selected" 2>/dev/null | tar -xf - \
        --strip-components=2 \
        --exclude="${archive_root}/manifest.json" \
        -C "$restore_root" \
        "${archive_root}/payload/" 2>/dev/null; then
        echo -e "${RED}[FAIL]${NC} Extraction failed."
        exit 1
    fi

    echo -e "${GREEN}[OK]${NC}   Restore complete."
    echo ""
    echo -e "  Restored to: $restore_root"
    echo ""
    echo -e "${YELLOW}[NOTE]${NC} Restart the OpenClaw gateway for changes to take effect."
    echo ""
}
