# AIO Launcher wrappers

Lua scripts for [AIO Launcher](https://play.google.com/store/apps/details?id=ru.execbit.aiolauncher)
that host this app's real native widgets inside AIO's home-screen surface,
so the same widgets can sit alongside AIO's other scriptable widgets and,
longer term, be added/removed/reordered by an agent driving AIO itself.

**Gotcha, confirmed by hours of on-device testing**: `obsidian-controller.lua`'s
`am broadcast` remote-command routing and `aio:add_widget/remove_widget/
move_widget/fold_widget()` both identify a script by its literal
**filename, including the `.lua` extension** (e.g. `obsidian-panel-2.lua`)
— not the script's declared `-- name =` title, despite AIO's own
`samples/tasker-widget-control.lua` implying otherwise. Getting this wrong
looks exactly like the command silently doing nothing (no error, no
crash) — a wildcard target (`script:*:<data>`) reaches every script's
`on_command` regardless of name and is the fastest way to confirm basic
routing works before chasing why a specific name doesn't match.

- `obsidian-note.lua` — wraps `ObsidianWidgetProvider` (checklist / plain-text
  note). Tapping a checklist row toggles it; tapping the title opens the
  full editor.
- `obsidian-slot-2.lua` / `-3.lua` / `-4.lua` — same idea as
  `obsidian-note.lua`, each bound to its own provider
  (`ObsidianWidgetProvider2`/`3`/`4`). Kept in the repo but don't expect
  them to work — see "Content slots" below for why a second instance of
  this kind of widget doesn't reliably update on screen.
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

## Panels — one unified surface for content and interactivity

`obsidian-panel-1/2/3/4.lua` have no native widget binding at all — they
render whatever the agent last pushed via `am broadcast`, and dispatch
taps through one small, general action vocabulary. This is the actual
answer to "a clean, unified, powerful surface the agent can drive
end-to-end" — one JSON envelope, three content modes, three action
verbs, covering display, file editing, arbitrary effects, and anything
needing real judgment, all with the same shape:

```
am broadcast -a ru.execbit.aiolauncher.COMMAND \
  --es cmd "script:obsidian-panel-2.lua:<json>"
```

**Content** (`<json>`'s `mode`):

- `{"mode":"text","title":"...","body":"line1\nline2","action":"<verb>:<arg>"}`
- `{"mode":"chart","points":[[ts_ms,value],...],"format":"x:date y:number","title":"...","show_grid":true,"action":"<verb>:<arg>"}`
  — a real line chart via AIO's own `ui:show_chart()`.
- `{"mode":"layout","elements":[[...],[...]],"actions":{"<idx>":"<verb>:<arg>"}}`
  — AIO's full declarative rich-UI element language (`README_RICH_UI.md`
  in `~/aiolauncher_scripts`): text, buttons, icons (FontAwesome or custom
  SVGs), progress bars — sized, colored, precisely positioned.
  `elements` passes straight into `gui{}` (a JSON array of tuples decodes
  to exactly the Lua table shape it expects). **Always include the
  options table, even empty** — `["text","...",{}]`, never
  `["text","..."]` — confirmed on-device that omitting it throws a LuaJ
  coercion error from `gui{}`'s internals for a JSON-decoded tuple, even
  though the equivalent bare 2-item Lua table literal is valid and
  demonstrated in AIO's own docs. `actions` maps a 1-based index to an
  action string — **the index counts every entry in `elements`
  positionally, including `new_line`/`spacer`, not just the "real"
  elements** — confirmed on-device after this cost real debugging time.
  For `elements = [text, new_line, button]`, the button is index 3, not
  2. Count array positions by hand, or push a throwaway version first and
  check which index actually fires (a temporary `debug:toast` in
  `on_click(idx)` is the fastest way), before trusting a guessed index.
- `{"mode":"clear"}` — resets to the placeholder state.

**Actions** (`action`/`actions` values, `<verb>:<arg>`, identical
regardless of mode or tap target):

- `open:<vault-relative-path>` — opens that note in the real editor
  (reuses `EditNoteActivity` via `ObsidianWidgetProvider`'s
  `ACTION_EDIT`, extended with an optional `note_path` extra since a
  panel has no bound widget instance to key off). The general "edit
  arbitrary files" mechanism — doesn't touch the native-widget bridge at
  all, but needs the `SYSTEM_ALERT_WINDOW` permission ("Display over
  other apps") granted once in Android settings. Confirmed on-device:
  Android 10+ silently blocks `startActivity()` from a `BroadcastReceiver`
  triggered by a plain background broadcast — which is exactly what a
  panel tap or `am broadcast` both are, as opposed to a real widget's
  `PendingIntent` fired via `bridge:click_handle()` (which is why *that*
  path never needed this and always just worked). No error, no crash —
  the broadcast arrives and the right code runs, the activity just never
  appears. `SYSTEM_ALERT_WINDOW`-holding apps are exempt from this
  restriction (the same mechanism automation apps like Tasker rely on).
- `broadcast:<json>` — sends an arbitrary Android broadcast:
  `{"action":"...","component":"...","extras":{...}}` (`component`
  optional). Covers "effects/call an API" generally — anything reachable
  by an Android intent (toggle a setting, open an app, hit another app's
  own broadcast interface, loop back into this app for a custom effect).
- `agent:<free text>` — forwards the text as a fresh prompt to
  `agent-host.mjs` via `AgentTriggerReceiver` (`app/src/main/java/com/
  obsidianwidget/AgentTriggerReceiver.kt`) — the same daemon the chat
  widget talks to. For anything needing real judgment rather than a fixed
  effect. Fire-and-forget: the agent's own subsequent actions (editing a
  file, pushing updated panel content) are the visible result, not a
  reply shown anywhere — the receiving agent should act, not just
  respond conversationally, since nobody's watching a chat transcript
  for this kind of prompt (it arrives prefixed `[Home screen: <panel>]`).
  Needs `agent-host.mjs` actually running (it's off by default to save
  battery — see `~/agent-host/control.sh`); a tap while it's down fails
  the WebSocket connection silently, same as the chat widget showing
  "offline" — confirmed on-device as the cause of an apparently-broken
  trigger that was really just a stopped daemon.

This is not a live web renderer: RemoteViews-style Android widgets and
AIO's own script sandbox both exclude WebView, so literal React/Ant
Design can't run inside a panel — `gui{}`'s element language is AIO's own
equivalent expressive layer, not a DOM. Content slots (below) are, in
principle, the better fit for a checklist you want to tap individual
items on — but see that section for why there's really only one that
works reliably.

**Spawn panels on demand, don't pre-provision and fold them.** A folded
widget still reserves a visible row — that's AIO's own chrome, not
something script code controls — so a panel that isn't currently needed
should be `remove`d via the controller, not folded, to actually reclaim
the space. Confirmed on-device: a panel's pushed content (`prefs`)
survives a remove + later re-add, since it's tied to the script file, not
any widget instance, so nothing is lost. The one exception is
`obsidian-controller.lua` itself, which must stay permanently active
(folded, never removed) — removing it leaves nothing to route a future
`add` command to, and recovering from that needs a manual re-add through
AIO's own UI, not `am broadcast`.

## Search-bar chat (`obsidian-agent-search.lua`)

A `type = "search"` script (no home-screen footprint at all) that shows a
"Chat with Agent" button under any typed query and switches into AIO's
built-in chat UI on tap (`search:chat_start()`/`on_chat()`). This is the
main way the user talks to the agent now, ahead of the `AgentWidgetProvider`
chat widget.

The interesting constraint: AIO's Lua sandbox has no blocking network
call — `http:post()` is async-callback only (`on_network_result`), and
there's no documented way to append a message into an already-open chat
session started with `chat_start()`. Confirmed on-device that calling
`chat_start()` *again* from `on_network_result` does successfully inject
the new reply into the visible conversation — that's what makes this
work at all, despite not being documented behavior.

Depends on a second endpoint added to `agent-host.mjs` alongside its
WebSocket protocol, since panels/search scripts can't hold a socket open
across a multi-second turn:

```
POST http://127.0.0.1:8178/chat
{"prompt": "...", "sessionId": "..."}   // sessionId optional, omit for a fresh session
->
{"reply": "...", "sessionId": "..."}    // once the whole turn completes -- no streaming
```

Reuses the same `claude -p` spawn logic as the WebSocket path; buffers
the visible text across `stream-json` events instead of forwarding them
one at a time. `agent-host.mjs` must be running (`~/agent-host/control.sh
start`) — same dependency as the panel `agent:` verb, same silent-failure
mode if it's down.

**Known gap, not yet built**: no streaming/progress visibility — the
script shows "Thinking..." as a placeholder and then the final reply,
nothing in between. Closing that gap needs `agent-host.mjs`'s `/chat` to
become job-based (`POST /chat/start` returns an id immediately, `GET
/chat/status/<id>` returns partial state) so the script can poll it via
`timer:start()`/`on_tick()` instead of blocking. `AgentChatActivity.kt`/
`AgentManager.kt` are the reference for full parity (streaming, session
history, MiMo fallback).

## Content slots — and why there's really only one

`obsidian-note.lua` handles "present some vault content" fully
generically — pinned note or daily note, checklist or plain text, whatever
`ACTION_CONFIGURE` points it at, with real per-row tap targets (checking
an item off updates the actual note file). The obvious next step —
`obsidian-slot-2/3/4.lua`, plain duplicates of it for more independent
slots — does NOT work, despite two rounds of investigation that looked
promising:

1. **First attempt**: assumed it was a staleness/timing bug (AIO caching
   an old `bridge:snapshot()`). Tried remove/re-add, longer delays,
   explicit `ACTION_REFRESH`, fold-toggling. None of it helped.
2. **Second attempt**: `ObsidianWidgetProvider2`/`3`/`4` (empty subclasses
   in the app, own manifest receiver + widget_info XML each) so each slot
   binds a genuinely separate Android component instead of sharing
   `ObsidianWidgetProvider`. This looked like it should fix a
   provider-keyed caching bug in AIO's bridge — but tested directly and
   it made no difference either: a widget bound through `Provider2`,
   freshly created, correctly configured (verified via `ACTION_DUMP_STATE`
   every single time), still never rendered past its first unconfigured
   state.

Conclusion: **only the first native widget ever bound through
`widgets:setup()` in an AIO session reliably receives live update
notifications; every subsequent one is frozen after its first render**,
regardless of which script or provider component it uses. This looks like
an AIO-side limitation in its script-widget bridge, not something fixable
from this app's side. The `ObsidianWidgetProvider2/3/4` classes and
`obsidian-slot-2/3/4.lua` are left in the repo (harmless, and the
per-provider structure may matter for other reasons later) but don't
expect a second one to actually update on screen.

**Use a panel instead** for any note beyond the one working slot — the
agent reads the file directly and pushes rendered content, which never
touches `widgets:setup()`/`bridge:snapshot()` at all, so this limitation
doesn't apply. The tradeoff is real but narrow: a panel has one whole-tile
tap action (open the linked note), not per-row toggles — a loss only for
content that's actually checklist-shaped; for prose it's not a
compromise at all.

If a second *genuinely interactive* checklist is ever required, the only
path likely to work is a real native Android widget added through AIO's
own regular "add widget" menu (bypassing AIO's Lua layer entirely, so
whatever this bridge limitation is doesn't apply) — at the cost of the
agent being unable to fold/reposition it via the controller, since it
isn't an AIO script. A deliberate, narrow tradeoff to make knowingly, not
a default.

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
