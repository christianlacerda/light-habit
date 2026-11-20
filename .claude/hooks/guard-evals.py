#!/usr/bin/env python3
"""PreToolUse guard: Claude must ask before changing the evals or the guards around them.

Protected:
  eval/                  the emulator scenarios (eval/out/, the run output, is not)
  tool/src/test/         the unit tests
  .claude/settings.json, .claude/hooks/, CLAUDE.md   the guards themselves

File tools (Edit, Write, NotebookEdit) touching a protected path always ask. A shell command
asks when it mentions a protected path (or runs inside one) and looks like it writes.
Asking, not denying: changing a test is sometimes right, but never silently.
"""

import json
import os
import re
import sys

PROTECTED = [
    re.compile(r"(^|/)eval/(?!out(/|$))"),
    re.compile(r"(^|/)tool/src/test(/|$)"),
    re.compile(r"(^|/)\.claude/(settings\.json|hooks(/|$))"),
    re.compile(r"(^|/)CLAUDE\.md$"),
]

# In shell commands paths are often relative after a `cd`, so bare names count as mentions too.
MENTION = re.compile(
    r"eval/(?!out\b)|\beval\b|src/test|Test\.kt\b|\bscenarios\.py\b|\bdevice\.py\b|\brun\.py\b"
    r"|\.claude/(settings\.json|hooks)|guard-evals|CLAUDE\.md"
)

WRITES = re.compile(
    r"\bsed\b[^|;&]*\s-i|\bperl\b[^|;&]*\s-[a-z]*i|\btee\b|\bmv\b|\bcp\b|\brm\b|\brmdir\b|\btruncate\b"
    r"|\bpatch\b|\bgit\s+(apply|am|checkout|restore|reset|rm|mv|stash|revert|cherry-pick)\b"
    r"|\bchmod\b|\bchattr\b|\bln\b|\binstall\b|\bdd\b|\btouch\b|\bawk\b[^|;&]*-i\s*inplace"
    r"|open\([^)]*['\"][wax+]|write_text|write_bytes|\.write\(|>"
)

HARMLESS_REDIRECTS = re.compile(r"\d?>&\d|&?\d?>\s*/dev/null|>\s*(\$S|/tmp/|\"?\$?TMPDIR)")


def ask(reason: str) -> None:
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "ask",
            "permissionDecisionReason": reason,
        }
    }))
    sys.exit(0)


def repo_relative(path: str, root: str, cwd: str) -> str:
    full = os.path.normpath(os.path.join(cwd or root, os.path.expanduser(path)))
    return os.path.relpath(full, root) if full.startswith(root + os.sep) else full


def main() -> None:
    event = json.load(sys.stdin)
    tool = event.get("tool_name", "")
    args = event.get("tool_input", {}) or {}
    root = os.path.realpath(os.environ.get("CLAUDE_PROJECT_DIR") or event.get("cwd") or os.getcwd())
    cwd = event.get("cwd") or root

    if tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
        path = args.get("file_path") or args.get("notebook_path") or ""
        rel = repo_relative(path, root, cwd)
        if any(p.search(rel) for p in PROTECTED):
            ask(f"{rel} is part of the eval or its guards. Changing it needs your approval: "
                "fix the app when a test fails, and change a test only when the spec changed or the test is wrong.")
        return

    if tool == "Bash":
        cmd = args.get("command", "")
        rel_cwd = repo_relative(cwd, root, root) + "/"
        in_protected = any(p.search(rel_cwd) for p in PROTECTED)
        writes = WRITES.search(HARMLESS_REDIRECTS.sub("", cmd))
        if writes and (in_protected or MENTION.search(cmd)):
            ask("This shell command may change the eval or its guards. Changing them needs your approval: "
                "fix the app when a test fails, and change a test only when the spec changed or the test is wrong.")


if __name__ == "__main__":
    main()
