# Habits

A habit tracker for the Light Phone III.

Three habits, seven days, one tap each. No streaks, no badges, no numbers — a
missed day renders like any other. The tool is an honest record of what you
did, not a scoreboard trying to make you do it.

This repository is a fork of [`lightphone/light-sdk`](https://github.com/lightphone/light-sdk).
Everything specific to Habits lives in [`tool/`](./tool); the rest is Light's
SDK, tracked unmodified against upstream so the tool always builds against the
current platform. Light's build server compiles only the `tool/` module anyway
(see [Building](#building) below).

## The screens

**Week grid** — the home screen. Each habit gets its name and a strip of seven
day cells. Tap a cell to record the day. Cells carry three weights, not two
states: trackable, still in the future, and before the habit existed. Ticking a
day that hasn't happened yet is deliberately impossible — a tick records what
you did, and the tool has no scheduling.

**Report** — one line per habit, one bar per month, six months at a time. Bar
height is days completed over days the habit could have been done, so a habit
created on the 20th isn't punished for the first nineteen days. The month still
running is drawn hollow and measured only against the days elapsed, so early
January doesn't read as a collapse.

**Edit mode** — rename, archive, delete, and add, inline on each habit's row.
Archiving preserves a habit's history and takes it out of the grid; unarchiving
brings it back. Deleting always asks first, because there is no undo on this
device.

**Settings** — which day the week starts on. That is the whole of it.

## Why three habits

Not an aesthetic constraint. It is what the LightOS 27×31 grid holds: the top
and bottom bars and the day letters leave roughly 21 units, and a habit block
costs about 6.5. A fourth would mean scrolling, and a habit tracker you have to
scroll is one you stop reading at a glance.

Some other decisions and the reasoning behind them are recorded in the source,
next to the code they constrain, rather than here.

## Building

**On the emulator.** Set up the LightOS emulator as a system app
([instructions](docs/system_app)), then:

```
./scripts/run-emulator.sh
```

This boots the `LightPhone3` AVD if it isn't already running, installs `:tool`,
and launches it.

**On a real Light Phone III.** There is no ADB on production LP3 hardware — it
exposes only an MTP interface — so installing means going through the Tool
Manager over Wi-Fi. Open it on the phone, then pass the URL from its QR code
(the trailing hex string is a single-use auth token, minted fresh on every Tool
Manager restart):

```
scripts/upload-to-device.sh 'https://10-0-0-5.my.local-ip.co:54449/#<token>'
```

Newer LightOS builds expose a `developer` root and are driven by upstream's
`:tool:uploadTool` Gradle task instead; the script's header explains which
generation each one speaks to.

**For distribution.** Light builds community tools themselves, from a public git
commit, against their own pinned copy of the SDK — see
[Sharing Your Tool](docs/sideloading) and [`builder/`](./builder). Their
extractor takes `tool/lighttool.toml`, `tool/build.gradle.kts`, and
`tool/src/main/{kotlin,java,res,assets}`, and nothing else from this repo.

## Not yet possible

**Reminders.** Nothing prompts you to log a habit. `getSystemService` is blocked
for tools, so there is no AlarmManager and no local notifications; push would
need a server. This is the tool's main weakness and it is a platform limit, not
an omission.

## The name in Portuguese

The tool is Habits in English and **Hábitos** on a device set to Portuguese.
`lighttool.toml` carries `label = "@string/tool_name"` rather than a literal,
and the translations live in `tool/src/main/res/values/` and `values-pt/`.

This works because the label is written straight into `android:label` and
LightOS reads it back with `PackageManager.getApplicationLabel()`, which
resolves the resource against the device's locale. Verified on the emulator:
the toolbox lists Habits under `en-US` and Hábitos under `pt-BR`, from one
build. Worth knowing that the metadata doc describes `label` as the literal
string users see, so a resource reference may be more than was intended there.

## Licence

The SDK is Light's, under the terms in [LICENSE](./LICENSE). The `tool/` module
is by Christian Lacerda.

---

Upstream's SDK documentation is unchanged and still applies:
[complete documentation](./docs), [repo layout](./docs/repo),
[tool metadata](./docs/tool_metadata).
