#!/usr/bin/env python3
"""Runs the Habits scenarios on the emulator.

    python3 eval/run.py                  # every scenario, deterministic checks only
    python3 eval/run.py --only rename    # one scenario (repeatable; prefix match)
    python3 eval/run.py --judge          # also have Claude grade screenshots against rubrics
    python3 eval/run.py --list

Needs the LightPhone3 emulator running with the tool installed (./scripts/run-emulator.sh).
Whatever the tool had stored and the device's clock setting are put back afterwards, even
when a scenario fails. Results land in eval/out/<timestamp>/.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
import traceback
from datetime import datetime
from pathlib import Path

from device import Device, DeviceError
from scenarios import SCENARIOS, Context, Scenario

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(__file__).resolve().parent / "out"

JUDGE_PROMPT = """You are grading one screen of "Habits", a deliberately minimal habit tracker for the \
Light Phone III: a black-and-white, near-square 1080x1240 display, with no colour and no gamification.

Read each of these screenshots with the Read tool:
{shots}

Rubric:
{rubric}

Judge only what the rubric asks, from what is visible. Reply with JSON alone, no prose around it:
{{"pass": true or false, "reasons": "one or two sentences naming what you saw"}}"""


def judge(scenario: Scenario, shots: list[str]) -> dict:
    if not shutil.which("claude"):
        return {"pass": None, "reasons": "the claude CLI is not on PATH"}
    prompt = JUDGE_PROMPT.format(shots="\n".join(f"- {p}" for p in shots), rubric=scenario.rubric)
    r = subprocess.run(
        ["claude", "-p", prompt, "--allowedTools", "Read", "--output-format", "json"],
        capture_output=True, text=True, cwd=ROOT, timeout=300,
    )
    if r.returncode != 0:
        return {"pass": None, "reasons": f"claude exited {r.returncode}: {r.stderr.strip()[:300]}"}
    text = json.loads(r.stdout).get("result", "")
    match = re.search(r"\{.*\}", text, re.S)
    try:
        verdict = json.loads(match.group(0)) if match else None
    except json.JSONDecodeError:
        verdict = None
    return verdict if isinstance(verdict, dict) else {"pass": None, "reasons": f"unparseable verdict: {text[:300]}"}


def run_one(d: Device, sc: Scenario, out_dir: Path, use_judge: bool) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    ctx = Context(d, out_dir)
    started = time.monotonic()
    error = None
    try:
        d.set_clock(sc.now)
        d.seed(sc.seed)
        sc.run(ctx)
    except Exception as e:  # a crash is a failed scenario, not a failed run
        error = f"{type(e).__name__}: {e}"
        (out_dir / "traceback.txt").write_text(traceback.format_exc())
        try:
            ctx.snap("at-error")
            (out_dir / "screen-at-error.xml").write_text(d.screen().xml)
        except Exception:
            pass
    result = {
        "name": sc.name,
        "summary": sc.summary,
        "passed": error is None and all(c["ok"] for c in ctx.checks),
        "checks": ctx.checks,
        "error": error,
        "screenshots": [str(Path(p).relative_to(out_dir.parent)) for p in ctx.shots],
        "seconds": round(time.monotonic() - started, 1),
    }
    if use_judge and sc.rubric and ctx.shots and error is None:
        result["judge"] = judge(sc, ctx.shots)
    return result


def report_md(results: list[dict], run_dir: Path) -> str:
    lines = [f"# Habits eval, {run_dir.name}", ""]
    passed = sum(r["passed"] for r in results)
    lines.append(f"**Deterministic checks: {passed}/{len(results)} scenarios pass.**")
    judged = [r for r in results if "judge" in r]
    if judged:
        jp = sum(r["judge"].get("pass") is True for r in judged)
        lines.append(f"**Judge: {jp}/{len(judged)} rubrics pass.**")
    lines.append("")
    for r in results:
        mark = "PASS" if r["passed"] else "FAIL"
        lines.append(f"## {mark} `{r['name']}`: {r['summary']}")
        for c in r["checks"]:
            lines.append(f"- {'✓' if c['ok'] else '✗'} {c['what']}" + (f" ({c['detail']})" if "detail" in c else ""))
        if r["error"]:
            lines.append(f"- ✗ **error:** {r['error']}")
        if "judge" in r:
            v = r["judge"].get("pass")
            lines.append(f"- judge: **{'pass' if v else 'fail' if v is False else 'no verdict'}**: {r['judge'].get('reasons', '')}")
        for shot in r["screenshots"]:
            lines.append(f"- screenshot: [{shot}]({shot})")
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", action="append", help="run scenarios whose name starts with this")
    ap.add_argument("--judge", action="store_true", help="grade screenshots against rubrics with `claude -p`")
    ap.add_argument("--list", action="store_true", help="list scenarios and exit")
    ap.add_argument("--serial", help="adb serial, if more than one device is attached")
    args = ap.parse_args()

    chosen = [s for s in SCENARIOS if not args.only or any(s.name.startswith(o) for o in args.only)]
    if args.list:
        for s in SCENARIOS:
            print(f"{s.name:28} {'[rubric] ' if s.rubric else '         '}{s.summary}")
        return 0
    if not chosen:
        print(f"no scenario matches {args.only}", file=sys.stderr)
        return 2

    d = Device(args.serial)
    try:
        d.preflight()
    except DeviceError as e:
        print(f"preflight: {e}", file=sys.stderr)
        return 2

    run_dir = OUT / datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True)
    saved_store = d.read_store()
    saved_auto_time = d.auto_time()
    results = []
    try:
        for sc in chosen:
            print(f"{sc.name:28} ", end="", flush=True)
            r = run_one(d, sc, run_dir / sc.name, args.judge)
            results.append(r)
            verdict = ""
            if "judge" in r:
                v = r["judge"].get("pass")
                verdict = f"  judge: {'pass' if v else 'FAIL' if v is False else '?'}"
            print(f"{'pass' if r['passed'] else 'FAIL'}  {r['seconds']:5.1f}s{verdict}")
            for c in r["checks"]:
                if not c["ok"]:
                    print(f"    ✗ {c['what']}" + (f" ({c['detail']})" if "detail" in c else ""))
            if r["error"]:
                print(f"    ✗ {r['error']}")
            if "judge" in r and r["judge"].get("pass") is not True:
                print(f"    judge: {r['judge'].get('reasons', '')}")
    finally:
        d.write_store(saved_store)
        d.restore_clock(saved_auto_time)
        d.stop()

    (run_dir / "results.json").write_text(json.dumps(results, indent=2))
    (run_dir / "report.md").write_text(report_md(results, run_dir))
    passed = sum(r["passed"] for r in results)
    print(f"\n{passed}/{len(results)} scenarios pass. Report: {run_dir / 'report.md'}")
    judge_failed = any(r.get("judge", {}).get("pass") is False for r in results)
    return 0 if passed == len(results) and not judge_failed else 1


if __name__ == "__main__":
    sys.exit(main())
