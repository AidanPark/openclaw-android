#!/usr/bin/env bash
# install-ai-tools.sh - Interactive AI CLI tools installer for Termux
# Usage: bash install-ai-tools.sh
#
# Presents a checkbox UI for selecting AI CLI tools to install/update.
# Non-interactive mode (piped input) skips selection automatically.
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

# ── Tool Definitions ────────────────────────────
TOOL_NAMES=("Claude Code" "Gemini CLI" "Codex CLI")
TOOL_ORGS=("Anthropic" "Google" "OpenAI")
TOOL_PACKAGES=("@anthropic-ai/claude-code" "@google/gemini-cli" "@openai/codex")
TOOL_COMMANDS=("claude" "gemini" "codex")
NUM_TOOLS=${#TOOL_NAMES[@]}

# ── State ───────────────────────────────────────
declare -a INSTALLED=()
declare -a VERSIONS=()
declare -a selected=()
cursor=0

# ── Detect Installed Tools ──────────────────────

detect_installed() {
    local i
    for ((i=0; i<NUM_TOOLS; i++)); do
        if command -v "${TOOL_COMMANDS[$i]}" &>/dev/null; then
            INSTALLED[$i]=true
            VERSIONS[$i]=$("${TOOL_COMMANDS[$i]}" --version 2>/dev/null | head -1 || echo "")
        else
            INSTALLED[$i]=false
            VERSIONS[$i]=""
        fi
    done
}

# ── Draw Checkbox Menu ──────────────────────────

draw_menu() {
    local i
    for ((i=0; i<NUM_TOOLS; i++)); do
        local check="[ ]"
        if [ "${selected[$i]}" = true ]; then
            check="[x]"
        fi

        local pointer="  "
        if [ "$i" -eq "$cursor" ]; then
            pointer="> "
        fi

        local suffix=""
        if [ "${INSTALLED[$i]}" = true ]; then
            if [ -n "${VERSIONS[$i]}" ]; then
                suffix=" ${DIM}— installed: ${VERSIONS[$i]}${NC}"
            else
                suffix=" ${DIM}— installed${NC}"
            fi
        fi

        if [ "$i" -eq "$cursor" ]; then
            echo -e "\r  ${BOLD}${pointer}${check} ${TOOL_NAMES[$i]} (${TOOL_ORGS[$i]})${NC}${suffix}\033[K"
        else
            echo -e "\r  ${pointer}${check} ${TOOL_NAMES[$i]} (${TOOL_ORGS[$i]})${suffix}\033[K"
        fi
    done
}

# ── Interactive Checkbox Selection ──────────────

run_checkbox() {
    local i

    # All selected by default
    for ((i=0; i<NUM_TOOLS; i++)); do
        selected[$i]=true
    done

    # Hide cursor
    printf "\033[?25l"
    trap 'printf "\033[?25h"' EXIT INT TERM

    echo ""
    echo -e "  ${BOLD}Select AI CLI tools to install:${NC}"
    echo -e "  ${DIM}↑↓ Move · Space Toggle · Enter Confirm · a All · n None${NC}"
    echo ""

    # Initial draw
    draw_menu

    # Input loop
    while true; do
        IFS= read -rsn1 key || { break; }

        case "$key" in
            $'\x1b')
                local seq=""
                read -rsn2 -t 0.1 seq || true
                case "${seq}" in
                    '[A') # Up arrow
                        if ((cursor > 0)); then
                            cursor=$((cursor - 1))
                        fi
                        ;;
                    '[B') # Down arrow
                        if ((cursor < NUM_TOOLS - 1)); then
                            cursor=$((cursor + 1))
                        fi
                        ;;
                esac
                ;;
            ' ') # Space — toggle selection
                if [ "${selected[$cursor]}" = true ]; then
                    selected[$cursor]=false
                else
                    selected[$cursor]=true
                fi
                ;;
            'a'|'A') # Select all
                for ((i=0; i<NUM_TOOLS; i++)); do
                    selected[$i]=true
                done
                ;;
            'n'|'N') # Select none
                for ((i=0; i<NUM_TOOLS; i++)); do
                    selected[$i]=false
                done
                ;;
            '') # Enter — confirm
                break
                ;;
        esac

        # Move cursor up and redraw
        printf "\033[%dA" "$NUM_TOOLS"
        draw_menu
    done

    # Restore cursor
    printf "\033[?25h"
    trap - EXIT INT TERM
    echo ""
}

# ── Install Selected Tools ──────────────────────

install_selected() {
    local i
    local count=0
    local fail=0
    local skip=0

    for ((i=0; i<NUM_TOOLS; i++)); do
        if [ "${selected[$i]}" = true ]; then
            echo "Installing ${TOOL_NAMES[$i]} (${TOOL_ORGS[$i]})..."
            if npm install -g "${TOOL_PACKAGES[$i]}" --no-fund --no-audit; then
                local ver=""
                ver=$("${TOOL_COMMANDS[$i]}" --version 2>/dev/null | head -1 || echo "")
                echo -e "${GREEN}[OK]${NC}   ${TOOL_NAMES[$i]} ${ver}"
                count=$((count + 1))
            else
                echo -e "${YELLOW}[WARN]${NC} ${TOOL_NAMES[$i]} installation failed (non-critical)"
                fail=$((fail + 1))
            fi
        else
            echo -e "${DIM}[SKIP]${NC} ${TOOL_NAMES[$i]}"
            skip=$((skip + 1))
        fi
    done

    if [ "$count" -gt 0 ] || [ "$fail" -gt 0 ]; then
        echo ""
    fi

    if [ "$count" -gt 0 ]; then
        echo -e "${GREEN}$count AI CLI tool(s) installed${NC}"
    fi

    if [ "$fail" -gt 0 ]; then
        echo -e "${YELLOW}$fail tool(s) failed — retry with: oa --update${NC}"
    fi

    if [ "$skip" -eq "$NUM_TOOLS" ]; then
        echo "No AI CLI tools selected"
    fi
}

# ── Main ────────────────────────────────────────

detect_installed

# Non-interactive mode: skip selection
if [ ! -t 0 ]; then
    echo "Non-interactive mode — skipping AI CLI tools selection"
    echo "To install AI CLI tools later, run: oa --update"
    exit 0
fi

run_checkbox
install_selected
