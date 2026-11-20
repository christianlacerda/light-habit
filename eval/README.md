# Habits eval

Two layers, from fast and exact to slow and visual.

## 1. Unit tests

```
./gradlew :tool:testDebugUnitTest
```

The rules (`model/HabitRules.kt`) and the report's arithmetic (`ui/MonthTrend.kt`) are plain
functions that take `today` as a parameter, so every rule is checked on fixed dates. These run
in seconds and need no device.

## 2. Scenarios on the emulator

```
./scripts/run-emulator.sh              # boots LightPhone3, installs the current build
python3 eval/run.py                    # every scenario, deterministic checks only
python3 eval/run.py --only report      # scenarios whose name starts with "report"
python3 eval/run.py --judge            # also have Claude grade screenshots against rubrics
python3 eval/run.py --list
```

Each scenario:
1. sets the device clock to a fixed date (Thursday Nov 19 2026);
2. writes a `HabitState` straight into the tool's DataStore;
3. drives the real app through adb;
4. checks the result twice: on screen through `uiautomator dump`, and in storage.

Day cells and report columns describe themselves to accessibility services (`Read, 2026-11-19,
done`; `Read, 2026-09, 50%, closed`). That lets the checks read exact state rather than
guess from pixels.

What only the pixels can show is written as a `rubric`: faint pre-creation squares, the hollow
running month, bar proportions, legibility. With `--judge`, each rubric and its screenshots go
to `claude -p`, and the verdict is recorded next to the deterministic result. Judging is opt-in,
so a plain run stays free and repeatable.

Results go to `eval/out/<timestamp>/`: `report.md`, `results.json`, and screenshots per
scenario. On error you also get the screen dump and a traceback. The exit code is non-zero if any check or judge verdict fails.

**Requirements:** Python 3 (standard library only) and a userdebug emulator image with test-keys, for `adb root`; see
[docs/system_app](../docs/system_app). After the run, the tool's stored data and the device
clock are put back the way they were, even when a scenario fails.

### Adding a scenario

Scenarios live in `scenarios.py` and are registered with `@scenario(summary, seed, rubric)`. Use
`habit_state(H(...))` to build the seed. Inside a scenario, use:
- `c.d.tap(...)`, `c.d.tap_cell(habit, day)`, `c.tap_row_action(habit, "ARCHIVE")` to act;
- `c.check(...)`, `c.expect_cell(...)` and `c.stored()` to check;
- `c.snap(label)` for any screen the rubric should see.

Notes on the LightOS emulator:
- Waking the screen shows LightOS's lock screen (`circle`), and unlocking lands on the LightOS
  home. `screen_off_on()` therefore reopens the tool without killing it.
- Edit mode has no "Archived" heading. An archived habit is a dimmed row that offers
  UNARCHIVE.
