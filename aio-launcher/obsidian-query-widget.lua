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
-- a ready-to-render envelope {mode,title,body,action}. See that file's
-- own header for how to add a new query there -- a registry entry, not
-- a new process or script.
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

local spec = nil          -- {mode, title, body, action}
local dialog_open = false
local settings_idx = nil  -- click index of the trailing "gear" line, set by render()

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
  if spec == nil then
    lines = { "<b>KB Query</b>", fmt.secondary("(loading…)") }
  else
    lines = { "<b>" .. (spec.title or "KB Query") .. "</b>" }
    for _, l in ipairs(split_lines(spec.body)) do
      table.insert(lines, l)
    end
  end
  table.insert(lines, fmt.secondary(I.gear .. "  " .. query_name() .. " · change"))
  settings_idx = #lines

  ui:show_lines(lines)
end

-- ── open the full source note ───────────────────────────────────────────────

local function open_note()
  local path = (spec and spec.action or ""):match("^open:(.*)$") or "personal/dashboard.s.md"
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
  open_note()
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
  local ok, decoded = pcall(json.decode, body)
  if not ok or type(decoded) ~= "table" or type(decoded.names) ~= "table" then return end
  files:write(NAMES_FILE, json.encode(decoded.names))
  dialog_open = true
  dialogs:show_list_dialog({ title = "Choose a query", lines = decoded.names, search = true })
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
