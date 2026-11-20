# Working on Habits

## Tests and evals are the spec, not obstacles

`tool/src/test/` (unit tests) and `eval/` (emulator scenarios and their rubrics) say how
Habits must behave. When one fails, the default assumption is that the app is wrong.

- **Fix the app, not the test.** Never loosen a check, delete or skip a scenario, widen an
  expected value, or rewrite a rubric to make a failure go away.
- **A test may change for two reasons only:** the spec changed (the user asked for different
  behaviour), or the test itself is wrong (it checks something the app never promised). Before
  editing one, stop and tell the user which it is and why, with the evidence — then wait.
- **Never change a test and the code it checks in the same commit.** The pre-commit hook in
  `.githooks/` rejects that; don't bypass it (`--no-verify`, `core.hooksPath`,
  `ALLOW_MIXED_EVAL_COMMIT`). Those are for the user alone.
- Adding new tests or scenarios for new behaviour is welcome; commit them separately from the
  implementation.
- Don't edit the guards: `.claude/settings.json`, `.claude/hooks/`, `.githooks/`, this file.

Changes to all of these paths ask the user for approval first (`.claude/hooks/guard-evals.py`).

## Running things

- Unit tests: `./gradlew :tool:testDebugUnitTest`
- Emulator scenarios: `./scripts/run-emulator.sh`, then `python3 eval/run.py` (`--judge` for
  the screenshot rubrics). See `eval/README.md`.
- Only `tool/lighttool.toml`, `tool/build.gradle.kts` and `tool/src/main/**` ship to Light.
