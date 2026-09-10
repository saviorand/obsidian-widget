# AIO Launcher wrappers

Lua scripts for [AIO Launcher](https://play.google.com/store/apps/details?id=ru.execbit.aiolauncher)
that host this app's real native widgets inside AIO's home-screen surface,
so the same widgets can sit alongside AIO's other scriptable widgets and,
longer term, be added/removed/reordered by an agent driving AIO itself.

- `obsidian-note.lua` — wraps `ObsidianWidgetProvider` (checklist / plain-text
  note). Tapping a checklist row toggles it; tapping the title opens the
  full editor.
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
