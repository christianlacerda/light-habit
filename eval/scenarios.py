"""The scenarios: each one is a date, a seeded HabitState, steps, checks and a rubric.

Checks are deterministic and read the screen through `uiautomator dump`. Day cells and
report columns describe themselves ("Read, 2026-11-19, done"; "Read, 2026-09, 50%, closed"),
so most rules are checked exactly. The `rubric` is for what only the pixels show, and is
graded by Claude only when the runner is given --judge.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Callable

from device import Device, DeviceError, Screen

# Thursday. With Sunday as week start, the week on screen is Nov 15–21.
TODAY = date(2026, 11, 19)
NOW = datetime(2026, 11, 19, 10, 0, 0)

EPOCH = date(1970, 1, 1)


def epoch(d: date) -> int:
    return (d - EPOCH).days


def days(start: date, end: date, step: int = 1) -> list[date]:
    return [start + timedelta(n) for n in range(0, (end - start).days + 1, step)]


def habit_state(*habits: dict, week_start: str = "SUNDAY") -> dict:
    """The app's HabitState JSON. Each habit's id is its name, lowercased."""
    return {
        "habits": [
            {
                "id": h["name"].lower(),
                "name": h["name"],
                "createdAt": epoch(h["created"]),
                **({"archivedAt": epoch(h["archived"])} if h.get("archived") else {}),
                "order": i,
            }
            for i, h in enumerate(habits)
        ],
        "completions": {h["name"].lower(): [epoch(d) for d in h.get("done", [])] for h in habits},
        "weekStart": week_start,
    }


def H(name: str, created: date, done: list[date] = (), archived: date | None = None) -> dict:
    return {"name": name, "created": created, "done": list(done), "archived": archived}


class Context:
    """What a scenario gets: the device, soft checks, and screenshots to hand to the judge."""

    def __init__(self, device: Device, out_dir):
        self.d = device
        self.out_dir = out_dir
        self.checks: list[dict] = []
        self.shots: list[str] = []

    def check(self, ok: bool, what: str, detail: str = "") -> bool:
        self.checks.append({"ok": bool(ok), "what": what, **({"detail": detail} if detail and not ok else {})})
        return ok

    def expect_cell(self, s: Screen, habit: str, day: date, want: str) -> bool:
        got = s.cell(habit, day)
        return self.check(got == want, f"{habit} on {day} is '{want}'", f"got {got!r}")

    def snap(self, label: str) -> None:
        path = self.out_dir / f"{len(self.shots) + 1:02d}-{label}.png"
        self.d.screenshot(path)
        self.shots.append(str(path))

    def stored(self) -> dict:
        return self.d.stored_state() or {}

    def stored_habit(self, habit_id: str) -> dict | None:
        return next((h for h in self.stored().get("habits", []) if h["id"] == habit_id), None)

    # edit-mode helpers

    def tap_row_action(self, habit: str, action: str) -> Screen:
        """Taps RENAME/ARCHIVE/DELETE/UNARCHIVE on the row of the named habit: the action
        nearest below its name."""
        s = self.d.screen()
        name = s.find(habit)
        if name is None:
            raise DeviceError(f"no row for {habit}; on screen: {s.labels()}")
        candidates = [n for n in s.nodes if n.text == action and n.bounds[1] >= name.bounds[1]]
        if not candidates:
            raise DeviceError(f"no {action} under {habit}; on screen: {s.labels()}")
        target = min(candidates, key=lambda n: n.bounds[1] - name.bounds[1])
        self.d.tap_xy(*target.center)
        return self.d.screen()

    def submit_name(self, text: str, submit: str, clear: bool = False) -> Screen:
        self.d.wait_for(lambda s: s.has(submit), what=f"the {submit} button")
        if clear:
            self.d.clear_text()
        self.d.type_text(text)
        self.d.tap(submit)
        return self.d.wait_for(lambda s: s.has("DONE") or s.has("Settings"), what="the grid")


@dataclass
class Scenario:
    name: str
    summary: str
    seed: dict | None
    run: Callable[[Context], None]
    rubric: str | None = None
    now: datetime = NOW
    tags: list[str] = field(default_factory=list)


SCENARIOS: list[Scenario] = []


def scenario(summary: str, seed: dict | None, rubric: str | None = None, **kw):
    def register(fn):
        SCENARIOS.append(Scenario(fn.__name__, summary, seed, fn, rubric, **kw))
        return fn

    return register


# --- the grid ------------------------------------------------------------------------------------


@scenario(
    "First run shows a calm empty state with no week navigation",
    seed=None,
    rubric=(
        "The empty home screen of a minimal habit tracker. Pass if: the message is legible and "
        "centred; the bottom bar shows its controls clearly; no week-navigation chevrons are shown; "
        "and every control the on-screen text tells the user to tap is actually visible on this screen."
    ),
)
def empty_state(c: Context):
    s = c.d.launch()
    c.check(s.has(contains="No habits yet"), "the empty-state hint is shown")
    c.check(not s.has("Previous week") and not s.has("Next week"), "no week chevrons on the empty state")
    c.check(s.has("Add habit") and not s.has("Edit habits"), "the + the hint names is on screen, in the pencil's place")
    c.snap("empty")
    s = c.d.tap("Add habit")
    c.check(s.has("ADD"), "+ opens the naming screen", f"on screen: {s.labels()}")


@scenario("Adding a habit trims the name and puts it on the grid", seed=None)
def add_habit(c: Context):
    c.d.launch()
    c.d.tap("Add habit")
    s = c.submit_name("  Read  ", "ADD")
    c.check(s.has("Edit habits") and not s.has("Add habit"), "with a habit on the grid, the pencil is back")
    c.check(s.has("Read"), "the habit is listed by its trimmed name", f"on screen: {s.labels()}")
    c.expect_cell(s, "Read", TODAY, "not done")
    stored = c.stored().get("habits", [])
    c.check([h["name"] for h in stored] == ["Read"], "one habit stored, named 'Read'", f"stored: {stored}")
    c.check(stored and stored[0]["createdAt"] == epoch(TODAY), "createdAt is today", f"stored: {stored}")


@scenario(
    "A fourth habit is refused with an explanation",
    seed=habit_state(H("Read", TODAY), H("Run", TODAY), H("Write", TODAY)),
    rubric=(
        "The user tried to add a fourth habit and the tool explained the limit of three. Pass if the "
        "message is fully legible, reads as an explanation rather than an error, and there is an "
        "obvious way to close it."
    ),
)
def habit_cap(c: Context):
    c.d.launch()
    c.d.tap("Edit habits")
    s = c.d.tap("Add habit")
    c.check(s.has(contains="3 habits is the limit"), "the limit message is shown", f"on screen: {s.labels()}")
    c.check(not s.has("ADD"), "the naming screen did not open")
    c.snap("limit")
    c.check(len(c.stored()["habits"]) == 3, "still three habits stored")


@scenario(
    "Future days cannot be ticked; today and earlier days can",
    seed=habit_state(H("Read", TODAY - timedelta(30))),
)
def future_days_inert(c: Context):
    s = c.d.launch()
    tomorrow = TODAY + timedelta(1)
    c.expect_cell(s, "Read", tomorrow, "future")
    s = c.d.tap_cell("Read", tomorrow)
    c.expect_cell(s, "Read", tomorrow, "future")
    s = c.d.tap_cell("Read", TODAY)
    c.expect_cell(s, "Read", TODAY, "done")
    done = c.stored()["completions"].get("read", [])
    c.check(done == [epoch(TODAY)], "only today is stored", f"stored: {done}")


@scenario(
    "A tap is saved at once: force-stopping right after it loses nothing",
    seed=habit_state(H("Read", TODAY - timedelta(30))),
)
def tick_persists(c: Context):
    s = c.d.launch()
    for day in (TODAY - timedelta(1), TODAY):
        node = s.find(startswith=f"Read, {day.isoformat()}, ")
        c.d.tap_xy(*node.center)
    # Tap, then pocket: the process dies before anything like an exit-time save could run.
    c.d.stop()
    s = c.d.launch()
    c.expect_cell(s, "Read", TODAY - timedelta(1), "done")
    c.expect_cell(s, "Read", TODAY, "done")


@scenario(
    "Days before a habit's creation week are inert and drawn fainter than future days",
    seed=habit_state(H("Old", date(2026, 11, 1), done=[date(2026, 11, 10)]), H("New", TODAY)),
    rubric=(
        "Two habits, 'Old' and 'New', shown for the week of Nov 8–14, before 'New' existed. Pass if: "
        "New's seven squares are all drawn (not missing) but clearly fainter than Old's; Old shows a "
        "filled square on Tuesday the 10th and outlined squares elsewhere; nothing about New's row "
        "reads as a failure or a broken streak."
    ),
)
def before_habit_cells(c: Context):
    c.d.launch()
    s = c.d.tap("Previous week")
    c.check(s.has("Nov 8–14"), "showing the week of Nov 8–14", f"on screen: {s.labels()}")
    for day in days(date(2026, 11, 8), date(2026, 11, 14)):
        c.expect_cell(s, "New", day, "before habit")
    c.expect_cell(s, "Old", date(2026, 11, 10), "done")
    s = c.d.tap_cell("New", date(2026, 11, 12))
    c.expect_cell(s, "New", date(2026, 11, 12), "before habit")
    c.check(c.stored()["completions"].get("new", []) == [], "nothing stored for New")
    c.snap("before-habit")


@scenario(
    "Week navigation stops at the earliest active habit and at the current week",
    seed=habit_state(H("Read", date(2026, 11, 3)), H("Gone", date(2026, 1, 5), archived=date(2026, 6, 1))),
)
def week_bounds(c: Context):
    s = c.d.launch()
    c.check(s.has("Nov 15–21"), "opens on the current week", f"on screen: {s.labels()}")
    c.check(not s.has("Next week"), "no › on the current week")
    c.d.tap("Previous week")
    s = c.d.tap("Previous week")
    c.check(s.has("Nov 1–7"), "two weeks back is Nov 1–7", f"on screen: {s.labels()}")
    c.check(
        not s.has("Previous week"),
        "no ‹ on the creation week (the archived habit from January doesn't extend it)",
    )
    c.d.tap("Next week")
    s = c.d.tap("Next week")
    c.check(s.has("Nov 15–21") and not s.has("Next week"), "back on the current week, › gone")


@scenario(
    "Screen-off keeps the week being backfilled but drops edit mode",
    seed=habit_state(H("Read", date(2026, 11, 3))),
)
def offset_survives_screen_off(c: Context):
    c.d.launch()
    c.d.tap("Previous week")
    s = c.d.tap("Edit habits")
    c.check(s.has("DONE"), "in edit mode")
    s = c.d.screen_off_on()
    c.check(s.has("Nov 8–14"), "still on the week of Nov 8–14", f"on screen: {s.labels()}")
    c.check(s.has("Edit habits") and not s.has("DONE"), "edit mode was dropped")
    s = c.d.tap_cell("Read", date(2026, 11, 10))
    done = c.stored()["completions"].get("read", [])
    c.check(done == [epoch(date(2026, 11, 10))], "the next tap lands on Nov 10, not this week", f"stored: {done}")


# --- managing habits -----------------------------------------------------------------------------


@scenario(
    "Archiving frees a slot and keeps history; unarchiving brings the ticks back",
    seed=habit_state(
        H("Read", date(2026, 11, 1), done=[date(2026, 11, 17), date(2026, 11, 18)]),
        H("Run", date(2026, 11, 1)),
        H("Write", date(2026, 11, 1)),
    ),
    rubric=(
        "Edit mode after archiving 'Read'. Pass if 'Read' is listed after the active habits, visibly "
        "set apart from them as archived, with UNARCHIVE and DELETE actions; the two active habits "
        "each show RENAME, ARCHIVE and DELETE; and nothing overlaps or is cut off."
    ),
)
def archive_unarchive(c: Context):
    c.d.launch()
    c.d.tap("Edit habits")
    s = c.tap_row_action("Read", "ARCHIVE")
    unarchive = s.find("UNARCHIVE")
    read_row = s.find("Read")
    c.check(unarchive is not None and read_row is not None and unarchive.bounds[1] > read_row.bounds[1],
            "Read's row now offers UNARCHIVE", f"on screen: {s.labels()}")
    c.snap("archived")
    read = c.stored_habit("read")
    c.check(read and read.get("archivedAt") == epoch(TODAY), "Read is stored as archived today", f"{read}")
    c.check(len(c.stored()["completions"].get("read", [])) == 2, "Read's two ticks are kept")
    c.d.tap("DONE")
    s = c.d.screen()
    c.check(s.cell("Read", TODAY) is None, "Read is off the grid")
    c.d.tap("Edit habits")
    c.tap_row_action("Read", "UNARCHIVE")
    s = c.d.tap("DONE")
    c.expect_cell(s, "Read", date(2026, 11, 17), "done")
    c.expect_cell(s, "Read", date(2026, 11, 18), "done")


@scenario(
    "Deleting asks first: CANCEL keeps the habit, DELETE removes it and its history",
    seed=habit_state(H("Read", date(2026, 11, 1), done=[date(2026, 11, 17)]), H("Run", date(2026, 11, 1))),
    rubric=(
        "A confirmation before permanently deleting the habit 'Read'. Pass if it names the habit, "
        "makes clear the deletion is permanent, and offers CANCEL and DELETE where neither could be "
        "hit by accident while aiming for the other."
    ),
)
def delete_confirms(c: Context):
    c.d.launch()
    c.d.tap("Edit habits")
    s = c.tap_row_action("Read", "DELETE")
    c.check(s.has("CANCEL"), "a confirmation with CANCEL is shown", f"on screen: {s.labels()}")
    c.snap("confirm")
    c.d.tap("CANCEL")
    c.check(c.stored_habit("read") is not None, "CANCEL keeps Read")
    c.tap_row_action("Read", "DELETE")
    s = c.d.screen()
    confirm = max((n for n in s.nodes if n.text == "DELETE"), key=lambda n: n.bounds[1])
    c.d.tap_xy(*confirm.center)
    stored = c.stored()
    c.check(c.stored_habit("read") is None, "Read is deleted")
    c.check("read" not in stored.get("completions", {}), "Read's history is deleted")
    c.check(c.stored_habit("run") is not None, "Run is untouched")


@scenario(
    "Renaming changes the label and nothing else",
    seed=habit_state(H("Read", date(2026, 11, 1), done=[date(2026, 11, 18)])),
)
def rename(c: Context):
    c.d.launch()
    c.d.tap("Edit habits")
    c.tap_row_action("Read", "RENAME")
    c.submit_name("Books", "SAVE", clear=True)
    s = c.d.tap("DONE")
    c.check(s.has("Books") and not s.has("Read"), "the grid shows Books", f"on screen: {s.labels()}")
    c.expect_cell(s, "Books", date(2026, 11, 18), "done")
    stored = c.stored_habit("read")
    c.check(stored and stored["name"] == "Books" and stored["createdAt"] == epoch(date(2026, 11, 1)),
            "same habit id, new name, same createdAt", f"{stored}")


@scenario(
    "Starting the week on Monday reorders the grid but moves no ticks",
    seed=habit_state(H("Read", date(2026, 11, 1), done=[date(2026, 11, 15), TODAY])),
)
def week_start_monday(c: Context):
    c.d.launch()
    c.d.tap("Settings")
    c.d.tap("Monday")
    s = c.d.tap("Back")
    c.check(s.has("Nov 16–22"), "the week is now Nov 16–22", f"on screen: {s.labels()}")
    letters = [n.text for n in sorted(s.nodes, key=lambda n: n.bounds[0]) if n.text in "SMTWF" and n.text]
    c.check(letters[:1] == ["M"], "the day letters start on M", f"letters: {letters}")
    c.expect_cell(s, "Read", TODAY, "done")
    s = c.d.tap("Previous week")
    c.expect_cell(s, "Read", date(2026, 11, 15), "done")
    c.check(c.stored().get("weekStart") == "MONDAY", "MONDAY is stored")


# --- the report ----------------------------------------------------------------------------------


@scenario(
    "Report bars measure trackable days only, and the running month is hollow",
    seed=habit_state(
        # Created Saturday June 20; its creation week starts June 14, so June has 17 trackable days.
        H("Read", date(2026, 6, 20),
          done=days(date(2026, 6, 14), date(2026, 6, 30))       # 17/17  -> 100%
          + days(date(2026, 7, 1), date(2026, 7, 8))             # 8/31   -> 26%
          + days(date(2026, 9, 1), date(2026, 9, 15))            # 15/30  -> 50%
          + days(date(2026, 11, 1), date(2026, 11, 19), 2)),     # 10/19  -> 53%, in progress
        # Created Monday Oct 5; Sunday start makes Oct 4–31 trackable, 28 days.
        H("Run", date(2026, 10, 5), done=days(date(2026, 10, 4), date(2026, 10, 17))),  # 14/28 -> 50%
    ),
    rubric=(
        "A six-month report, JUN–NOV 2026, one chart per habit with one bar per month. Expected bar "
        "heights as a share of the chart: Read — JUN 100%, JUL 26%, AUG 0%, SEP 50%, OCT 0%, NOV 53%; "
        "Run — JUN to SEP no bar at all, OCT 50%, NOV 0%. Pass if the bar heights are visibly in those "
        "proportions, the NOV bar is drawn hollow (outline only) while finished months are solid, every "
        "month label sits under its own bar with NOV marked as current, and nothing is clipped."
    ),
)
def report_bars(c: Context):
    c.d.launch()
    s = c.d.tap("REPORT")
    c.check(s.has("JUN – NOV 2026"), "window is JUN – NOV 2026", f"on screen: {s.labels()}")
    expected = {
        ("Read", "2026-06"): "100%, closed",
        ("Read", "2026-07"): "26%, closed",
        ("Read", "2026-08"): "0%, closed",
        ("Read", "2026-09"): "50%, closed",
        ("Read", "2026-10"): "0%, closed",
        ("Read", "2026-11"): "53%, in progress",
        ("Run", "2026-09"): "not tracked",
        ("Run", "2026-10"): "50%, closed",
        ("Run", "2026-11"): "0%, in progress",
    }
    for (habit, month), want in expected.items():
        got = s.bar(habit, month)
        c.check(got == want, f"{habit} {month} is {want}", f"got {got!r}")
    c.snap("report")


@scenario(
    "Report paging goes back to the first recorded month and no further",
    seed=habit_state(H("Read", date(2026, 3, 10), done=[date(2026, 3, 10)])),
)
def report_paging(c: Context):
    c.d.launch()
    s = c.d.tap("REPORT")
    c.check(not s.has("Later months"), "no later window from the current one")
    s = c.d.tap("Earlier months")
    c.check(s.has("DEC 2025 – MAY 2026"), "one page back is DEC 2025 – MAY 2026", f"on screen: {s.labels()}")
    c.check(not s.has("Earlier months"), "and that is the floor: March 2026 is on it")
    c.check(s.bar("Read", "2026-02") == "not tracked", "February predates the habit")
    s = c.d.tap("Later months")
    c.check(s.has("JUN – NOV 2026"), "› returns to the current window")

