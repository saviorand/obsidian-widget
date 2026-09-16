-- name = "KB Dashboard"
-- description = "Live urgent/active summary from the self/ knowledge base"
-- type = "widget"
-- foldable = "true"
-- on_resume_when_folding = "true"
-- author = "Val"
-- version = "1.0"

-- Talks straight to scrolls-host.mjs's `/widgets/dashboard-urgent` HTTP
-- route (127.0.0.1:8137) -- the same process already running for the
-- Obsidian plugin's WebSocket/LSP connection, not a separate bridge. See
-- that file's `WIDGETS` registry for what's actually being computed
-- (glibc-runner `scrolls query`, ~0.8s, no proot-distro) and how to add a
-- second live widget there. Unauthenticated on purpose: read-only, and
-- loopback binding is the real boundary, same reasoning tg-bridge.py's
-- /pair gives for why *that* endpoint is left open.
--
-- If the card just shows "(bridge unreachable)": scrolls-host isn't
-- running (start-scrolls-host.sh should have it up via Termux:Boot;
-- ~/scrolls-host/host.pid + `ps aux | grep scrolls-host` to check by hand).

local json = require("json")
local fmt  = require("fmt")

local BRIDGE = "http://127.0.0.1:8137/widgets/dashboard-urgent"

local LOCAL_FILE = "kb_dashboard_local.json"  -- last-known envelope, survives a fetch failure
local NET_FILE   = "kb_dashboard_net.txt"     -- survives AIO's Lua resets between entry points

local spec = nil  -- {mode, title, body, action}

local I = {
  warn = "%%fa:triangle-exclamation%%",
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
  http:get(BRIDGE, "fetch")
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
    if spec == nil then
      ui:show_text("…")
    else
      -- title already carries the count ("Dashboard — 6 urgent")
      ui:show_text(spec.title or "Dashboard")
    end
    return
  end

  ui:set_title("Dashboard")

  if spec == nil then
    ui:show_lines({ "<b>Dashboard</b>", fmt.secondary("(loading…)") })
    return
  end

  local lines = { "<b>" .. (spec.title or "Dashboard") .. "</b>" }
  for _, l in ipairs(split_lines(spec.body)) do
    table.insert(lines, l)
  end
  ui:show_lines(lines)
end

-- ── open the full dashboard note ────────────────────────────────────────────

local function open_dashboard()
  local path = (spec and spec.action or ""):match("^open:(.*)$") or "self/dashboard.s.md"
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
  do_fetch()
end

function on_resume()
  load_local()
  render()
  do_fetch()
end

function on_alarm()
  do_fetch()
end

function on_click(idx)
  if ui:is_folded() then return end
  open_dashboard()
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
