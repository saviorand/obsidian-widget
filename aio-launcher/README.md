# AIO Launcher wrappers

Lua scripts for [AIO Launcher](https://play.google.com/store/apps/details?id=ru.execbit.aiolauncher)
that host this app's real native widgets inside AIO's home-screen surface,
so the same widgets can sit alongside AIO's other scriptable widgets and,
longer term, be added/removed/reordered by an agent driving AIO itself.

- `obsidian-note.lua` — wraps `ObsidianWidgetProvider` (checklist / plain-text
  note). Tapping a checklist row toggles it; tapping the title opens the
  full editor.
- `obsidian-slot-2.lua` / `-3.lua` / `-4.lua` — byte-identical to
  `obsidian-note.lua` except their `-- name =` metadata, each an
  independent instance of the same provider. See "Content slots" below.
- `obsidian-panel-1/2/3/4.lua` — no native widget binding; render whatever
  text the agent pushes via `am broadcast`. See "Panels" below.
- `obsidian-notes.lua` — wraps `NotesWidgetProvider` (notes browser).
- `obsidian-agent.lua` — wraps `AgentWidgetProvider` (agent chat); tapping
  it opens `AgentChatActivity`.
- `obsidian-widget-dumper.lua` — diagnostic tool, not a real wrapper. Dumps
  a widget's view tree (`bridge:dump_tree()`/`dump_colors()`/`snapshot()`)
  so a new wrapper can find the right `resource_id`s. Needs AIO's newer
  `bridge:snapshot()`/`click_handle()` APIs (AIO 7.5.0-beta2+ confirmed).
- `obsidian-controller.lua` — not a widget wrapper either. Exposes
  `aio:add_widget/remove_widget/move_widget/fold_widget()` to `am
  broadcast`, so an agent can rearrange the home screen itself (add,
  remove, reorder, fold any already-imported widget or script — including
  AIO's own built-ins, not just this app's three wrappers), not just
  reconfigure a widget that's already placed. See its own header comment
  for the exact command shape and setup step (AIO Settings -> Tasker ->
  Remote API). Current state (available + active widgets) is reported back
  into the vault as `.aio-layout-state.json` via `AioBridgeReceiver` — a
  small generic write-back sink in the app (`app/src/main/java/com/
  obsidianwidget/AioBridgeReceiver.kt`) that any script can use to persist
  arbitrary state Android/AIO owns but this app has no other way to
  observe.

## How this works

AIO Launcher can host a real Android AppWidget inside a Lua script via
`widgets:setup(provider)` / `bridge:snapshot()` (reads the widget's
RemoteViews tree) / `bridge:click_handle(target)` (fires a row's real
click, same as tapping the native widget). The wrapper reads that tree by
matching on `resource_id`s (found via the dumper) and re-renders it as an
AIO list, forwarding taps back through `click_handle`.

## Refresh race (fixed)

Each wrapper's `refresh()` sends the app's own `ACTION_REFRESH` broadcast
before calling `widgets:request_updates()`, since AIO's cached RemoteViews
tree doesn't know about edits made outside the app (e.g. editing a note
directly in Obsidian). `send_broadcast` is fire-and-forget, and
`ACTION_REFRESH` itself just fires a *second* broadcast internally
(`updateAllWidgets` → `ACTION_APPWIDGET_UPDATE`) before the real SAF file
read happens — so `request_updates()` called immediately after was
reliably racing ahead of the actual refresh and re-showing stale content.
Fixed by also re-requesting an update ~2 seconds later via AIO's
`on_tick()` (a per-second callback while the widget is visible) once
`refresh()` runs.

## Deploying a script

Android's scoped storage blocks browsing into another app's
`Android/data/<pkg>/files/` folder from any file manager, so scripts can't
be dropped straight into AIO's own directory. Instead:

1. Copy the `.lua` file to `~/storage/downloads/`.
2. In AIO Store → **Add Script**, pick it via the system file picker
   (a single-file pick, which scoped storage does allow).
3. Long-press the resulting widget → set it up. Reimporting an updated
   script can allocate a fresh widget ID (AIO doesn't always preserve the
   old one across a script-file replace) — check the widget still shows
   the right content, and reconfigure via `ACTION_CONFIGURE` if not.

## Panels — fully agent-programmable content

`obsidian-panel-1/2/3/4.lua` have no native widget binding at all — they
just render whatever text the agent last pushed, via `am broadcast`:

```
am broadcast -a ru.execbit.aiolauncher.COMMAND \
  --es cmd "script:panel 1:<title>
<body line 1>
<body line 2>..."
```

`"script:panel 1:clear"` resets it. A leading `@<vault-relative-path>`
line makes the whole panel tappable, opening that note in the real editor
(reuses `EditNoteActivity` via `ObsidianWidgetProvider`'s `ACTION_EDIT`,
extended with an optional `note_path` extra for exactly this — a
broadcaster with no bound widget instance couldn't otherwise say which
note to open). See the script's own header comment for the exact escaping
notes (use a real embedded newline in the `--es` value, not the two
characters `\n`).

This is the actual answer to "one universal, hot-swappable widget type":
the agent already reads the vault directly with its own tools, so it can
compute *anything* — a note's content, a summary across several notes, a
checklist it formats itself, an agent-chat digest — and push the finished
text here. Content slots (below) are still the better fit specifically
for a checklist you want to tap individual items on, since only a widget
bound to the real native provider has real per-row click targets; a panel
has exactly one tap action for the whole thing (open the linked note, if
any).

## Content slots

`obsidian-note.lua` already handles "present some vault content" fully
generically — pinned note or daily note, checklist or plain text, whatever
`ACTION_CONFIGURE` points it at. So rather than a distinct widget type per
kind of data, `obsidian-slot-2/3/4.lua` are plain duplicates of it: more
independent instances of the same flexible widget, so most of the screen
doesn't need to be permanently allocated to specific content. Fold the
ones you're not using (`obsidian-controller.lua`'s `fold` op) and have the
agent point an unfolded one at whatever note is relevant right now
(`ACTION_CONFIGURE`'s `pin_note_paths`), unfolding more as needed.

This only applies to *content* — `obsidian-agent.lua` (a chat UI) and
`obsidian-notes.lua` (a navigation index) aren't "data slots" in the same
sense and stay as dedicated, single-instance widgets.

Each slot is a separate file rather than one script cloned N times:
AIO's native widget-cloning (`clonable` in `available_widgets()`) is
documented for a few builtin widgets (My Apps, Contacts) but nothing in
the API confirms it works for a custom script with independent
`widgets:setup()` state per clone, or that `prefs` storage would stay
scoped per clone rather than colliding. Separate files sidestep that
uncertainty entirely — `prefs` is already known to be scoped per script
file, so each slot's own widget id can never collide with another's.

Adding a 5th+ slot later is the same recipe: copy `obsidian-note.lua`,
change the `-- name =` line, import it.

## Known v1 gaps

- `obsidian-note.lua`: settings gear and cycle-note arrow aren't wired —
  reconfigure via `ACTION_CONFIGURE` broadcasts instead (see the vault's
  `CLAUDE.md`). Title tap opens the in-app editor rather than deep-linking
  into real Obsidian.
- `obsidian-controller.lua` can't import a brand-new script — only
  add/remove/move/fold widgets and scripts already known to AIO (imported
  once via AIO Store's Add Script picker). A genuinely new custom widget
  (e.g. one that renders some new kind of data) still needs one human tap
  to import before the agent can place or reconfigure it.
