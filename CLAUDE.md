# Working on Habits

## Tests and evals are the spec, not obstacles

`tool/src/test/` (unit tests) and `eval/` (emulator scenarios and their rubrics) say how
Habits must behave. When one fails, the default assumption is that the app is wrong.

- **Fix the app, not the test.** Never loosen a check, delete or skip a scenario, widen an
  expected value, or rewrite a rubric to make a failure go away.
- **A test may change for two reasons only:** the spec changed (the user asked for different
  behaviour), or the test itself is wrong (it checks something the app never promised). Before
  editing one, stop and tell the user which it is and why, with the evidence — then wait.
- **Keep the user in the loop.** Every change to a test or scenario, new ones included, needs
  their approval. When reporting back, say which tests or scenarios changed and why, in plain
  words, so the change is visible without reading the diff.
- Adding new tests or scenarios for new behaviour is welcome.
- Don't edit the guards: `.claude/settings.json`, `.claude/hooks/`, this file.

Changes to all of these paths ask the user for approval first (`.claude/hooks/guard-evals.py`).

## Running things

- Unit tests: `./gradlew :tool:testDebugUnitTest`
- Emulator scenarios: `./scripts/run-emulator.sh`, then `python3 eval/run.py` (`--judge` for
  the screenshot rubrics). See `eval/README.md`. Run them headless (the script's default);
  never open the emulator window just to test, and leave one the user opened alone.
- Only `tool/lighttool.toml`, `tool/build.gradle.kts` and `tool/src/main/**` ship to Light.
