-- name = "KB Query Widget"
-- description = "Pick any registered KB query and show its live results"
-- type = "widget"
-- foldable = "true"
-- on_resume_when_folding = "true"
-- author = "Val"
-- version = "1.0"

-- Talks to scrolls-host.mjs's WIDGETS registry (127.0.0.1:8137) -- the
-- same already-running process the Obsidian plugin's WS/LSP connection
-- uses. GET /widgets lists what's registered; GET /widgets/:name returns
-- a ready-to-render envelope {mode,title,body,action,rows}. `rows` is
-- `{text,action}[]`, one per displayed line, letting a specific KB fact
-- (`*a concept* has link *a value*.` in shared.s.md) send an individual
-- row's tap to its own note/URL instead of always opening the widget's
-- source file; `body`/`action` stay as the whole-widget fallback for rows
-- with no link of their own. See that file's own header for how to add a
-- new query there -- a registry entry, not a new process or script.
--
-- Picks ONE query at a time (prefs.query_name, default "dashboard-urgent")
-- -- tap the gear line to change it. For a SECOND, independently
-- configured widget: copy this file under a new filename and import that
-- too, same as this project's obsidian-panel-1/2/3/4.lua already do for
-- the same reason. Confirmed empirically (aio:available_widgets(), every
-- script-type entry reports clonable:false, unlike certain native
-- built-ins like "My apps") that AIO scripts aren't clonable here -- two
-- placements of the same script share the same prefs, so there's no
-- per-instance storage to configure independently even if both were on
-- screen at once.
--
-- If the card just shows "bridge unreachable": scrolls-host isn't running
-- (start-scrolls-host.sh should have it up via Termux:Boot;
-- ~/scrolls-host/host.pid + `ps aux | grep scrolls-host` to check by hand).

local json = require("json")
local fmt  = require("fmt")
local prefs = require "prefs"

local BRIDGE = "http://127.0.0.1:8137"
local DEFAULT_QUERY = "dashboard-urgent"

local LOCAL_FILE = "kb_query_local.json"  -- last-known envelope, survives a fetch failure
local NAMES_FILE = "kb_query_names.json"  -- last-fetched picker list, durable across resets
local NET_FILE   = "kb_query_net.txt"     -- survives AIO's Lua resets between entry points
local RETRY_FILE = "kb_query_retry.txt"   -- retry counter for the list fetch, see do_list()
local MAX_LIST_RETRIES = 2  -- a single retry wasn't enough headroom for the cold-connection hiccup below

local spec = nil          -- {mode, title, body, action, rows}
local dialog_open = false
local settings_idx = nil  -- click index of the trailing "gear" line, set by render()
local first_row_idx = nil -- click index of the first spec.rows[] line, set by render()

local I = {
  warn = "%%fa:triangle-exclamation%%",
  gear = "%%fa:gear%%",
}

-- ── local persistence ────────────────────────────────────────────────────────

local function save_local()
  files:write(LOCAL_FILE, json.encode(spec))
end

local function load_local()
  local txt = files:read(LOCAL_FILE)
  if not txt or txt == "" then return false end
  local ok, decoded = pcall(json.decode, txt)
  if ok and type(decoded) == "table" then
    spec = decoded
    return true
  end
  return false
end

local function query_name()
  local n = prefs.query_name
  if n == nil or n == "" then return DEFAULT_QUERY end
  return n
end

local function get_net_state()
  return files:read(NET_FILE) or "idle"
end

local function set_net_state(s)
  if s == "idle" then files:delete(NET_FILE) else files:write(NET_FILE, s) end
end

-- ── network ──────────────────────────────────────────────────────────────────

local function do_fetch()
  if get_net_state() ~= "idle" then return end
  set_net_state("fetch")
  http:get(BRIDGE .. "/widgets/" .. query_name(), "fetch")
end

-- Automatic retries on failure, no user-visible delay -- observed
-- on-device: the first loopback HTTP call in a while occasionally times
-- out or errors on Android's client side even though the server answers
-- instantly every time (confirmed via server-side request logging, every
-- /widgets call got a 200) -- a cold-connection hiccup, not a real
-- failure. One retry wasn't consistently enough to clear it (confirmed:
-- user still saw "bridge unreachable" on the first tap and had to tap
-- again by hand) -- MAX_LIST_RETRIES gives it more headroom before
-- actually giving up.
local function do_list(retry_count)
  retry_count = retry_count or 0
  if get_net_state() ~= "idle" then return end
  set_net_state("list")
  files:write(RETRY_FILE, tostring(retry_count))
  http:get(BRIDGE .. "/widgets", "list")
end

-- ── render ───────────────────────────────────────────────────────────────────

local function split_lines(s)
  local lines = {}
  for line in ((s or "") .. "\n"):gmatch("(.-)\n") do
    table.insert(lines, line)
  end
  return lines
end

function render()
  if ui:is_folded() then
    ui:show_text(spec and (spec.title or "KB query") or "…")
    return
  end

  ui:set_title("KB Query")

  local lines
  first_row_idx = nil
  if spec == nil then
    lines = { "<b>KB Query</b>", fmt.secondary("(loading…)") }
  else
    lines = { "<b>" .. (spec.title or "KB Query") .. "</b>" }
    if type(spec.rows) == "table" and #spec.rows > 0 then
      -- Built straight from spec.rows[], not split_lines(spec.body), so
      -- each line's click index maps 1:1 to spec.rows -- a per-row tap
      -- target (a linked URL or note) needs that correspondence to hold.
      first_row_idx = #lines + 1
      for _, r in ipairs(spec.rows) do
        table.insert(lines, r.text or "")
      end
    else
      -- A cached envelope from before per-row actions existed (or a
      -- widget format that never sets rows) has no rows -- fall back to
      -- the flat body text, same as always.
      for _, l in ipairs(split_lines(spec.body)) do
        table.insert(lines, l)
      end
    end
  end
  table.insert(lines, fmt.secondary(I.gear .. "  " .. query_name() .. " · change"))
  settings_idx = #lines

  ui:show_lines(lines)
end

-- ── open a row's (or the widget's default) tap target ───────────────────────

-- Two action shapes a server-side `has link` fact (or the widget's own
-- default) can produce: `"open:<vault-relative-path>"` for a note via the
-- Obsidian widget plugin's own broadcast, or a bare `https://...` URL for
-- an external link via the system browser. Anything else (missing, or a
-- scheme this doesn't recognize) falls back to the dashboard note, same
-- default this always had before per-row actions existed.
local function open_action(action)
  action = action or ""
  local url = action:match("^(https?://.+)$")
  if url then
    system:open_browser(url)
    return
  end
  local path = action:match("^open:(.*)$") or "personal/dashboard.s.md"
  intent:send_broadcast{
    action = "com.obsidianwidget.ACTION_EDIT",
    component = "com.obsidianwidget/com.obsidianwidget.ObsidianWidgetProvider",
    extras = { note_path = path },
  }
end

-- ── entry points ─────────────────────────────────────────────────────────────

function on_load()
  load_local()
  render()
  set_net_state("idle")  -- a resumed process shouldn't trust a flag a crashed/killed one left set
  do_fetch()
end

function on_resume()
  load_local()
  render()
  -- The user actively looking at the widget is itself a strong enough signal
  -- to override a stuck flag -- if a previous fetch's callback never landed
  -- (e.g. mid-restart on the bridge side), do_fetch()'s "already busy" guard
  -- would otherwise wedge forever with no way to self-heal.
  set_net_state("idle")
  do_fetch()
end

function on_alarm()
  do_fetch()
end

function on_click(idx)
  if ui:is_folded() then return end
  if idx == 1 then return end  -- title line
  if settings_idx and idx == settings_idx then
    do_list()
    return
  end
  if first_row_idx and type(spec) == "table" and type(spec.rows) == "table" then
    local row = spec.rows[idx - first_row_idx + 1]
    if row then
      open_action(row.action)
      return
    end
  end
  open_action(spec and spec.action)
end

function on_network_result_fetch(body, code)
  set_net_state("idle")
  if code ~= 200 then
    ui:show_text(I.warn .. "  bridge unreachable (" .. tostring(code) .. ")")
    return
  end
  local ok, decoded = pcall(json.decode, body)
  if not ok or type(decoded) ~= "table" then return end
  spec = decoded
  save_local()
  render()
end

function on_network_error_fetch(msg)
  set_net_state("idle")
  if spec == nil then
    ui:show_text(I.warn .. "  bridge unreachable")
  end
  -- otherwise leave the last-known content on screen
end

local function list_failed()
  local retry_count = tonumber(files:read(RETRY_FILE)) or 0
  if retry_count < MAX_LIST_RETRIES then
    do_list(retry_count + 1)
    return
  end
  ui:show_toast("Couldn't load query list (bridge unreachable?)")
end

function on_network_result_list(body, code)
  set_net_state("idle")
  if code ~= 200 then
    list_failed()
    return
  end
  -- Guard against a stacked second dialog: http:get() has no cancel, so a
  -- slow original request and a retry it triggered can BOTH eventually
  -- land as separate success callbacks. If a picker is already up from an
  -- earlier one, a second show_list_dialog() call stacks underneath it --
  -- tapping the top one closes it and reveals the second, which looks
  -- exactly like "the picker didn't close."
  if dialog_open then return end
  local ok, decoded = pcall(json.decode, body)
  if not ok or type(decoded) ~= "table" or type(decoded.names) ~= "table" then return end
  files:write(NAMES_FILE, json.encode(decoded.names))
  dialog_open = true
  -- `dialogs:show_list_dialog`, not `ui:show_list_dialog` -- the on-device
  -- app itself flags `ui:show_list_dialog` as deprecated at runtime, and
  -- the current upstream README (github.com/zobnin/aiolauncher_scripts)
  -- documents it under the `dialogs` module. The `ui:` form only appears
  -- in that repo's older sample scripts, which the README/CHANGELOG don't
  -- otherwise corroborate as current -- a previous pass here got this
  -- backwards on first read; the runtime warning is the ground truth.
  dialogs:show_list_dialog({ title = "Choose a query", lines = decoded.names, search = false })
end

function on_network_error_list(msg)
  set_net_state("idle")
  list_failed()
end

function on_dialog_action(value)
  if not dialog_open then return end
  dialog_open = false
  if type(value) ~= "number" or value < 1 then return end  -- dismissed (back/outside tap)
  local txt = files:read(NAMES_FILE)
  if not txt or txt == "" then return end
  local ok, names = pcall(json.decode, txt)
  if not ok or type(names) ~= "table" then return end
  local picked = names[value]
  if not picked then return end
  prefs.query_name = picked
  spec = nil
  save_local()
  render()
  do_fetch()
end
