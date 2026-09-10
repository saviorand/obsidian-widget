-- name = "Obsidian Controller"
-- description = "Headless add/remove/move/fold control for the home screen, driven by am broadcast"
-- type = "widget"
-- version = "1.0"
-- aio_version = "7.5.0-beta2"

-- One-time setup this script can't do for itself:
--   AIO Settings -> Tasker -> Remote API -> enable it, note the password.
-- (This is AIO's own remote-control gate, unrelated to the real Tasker app —
-- no Tasker install needed to use it this way.) On a device with no
-- password set, omit --es password entirely.
--
-- Command shape:
--
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd "script:obsidian-controller.lua:<op>=:=<name>=:=<arg>"
--
-- IMPORTANT, confirmed by testing on-device (contradicts AIO's own
-- samples/tasker-widget-control.lua, whose comment implies the script's
-- declared "-- name =" title, lowercased, is the routing key -- that
-- never worked here): the identifier AIO actually routes on_command by
-- is the script's literal FILENAME, including the ".lua" extension --
-- e.g. "obsidian-controller.lua", not "Obsidian Controller" or
-- "obsidian controller". A wildcard target ("script:*:<data>") reaches
-- every script's on_command regardless of name and is useful for
-- confirming routing works at all before chasing a specific name.
--
-- This is also the exact string aio:add_widget/remove_widget/move_widget/
-- fold_widget() expect as <widget-name> below (confirmed against
-- aio:available_widgets()'s "name" field: AIO's own built-ins get short
-- internal ids like "weather", but every script -- including this one --
-- is listed by its filename+extension).
--
-- Ops:
--   add=:=<widget-name>=:=[position]   add an already-imported widget/script
--   remove=:=<widget-name>=:=          remove it from the screen
--   move=:=<widget-name>=:=<position>  reorder it
--   fold=:=<widget-name>=:=<true|false>
--   list=:=                            no-op; just refreshes the report below
--
-- Run `list` once and read .aio-layout-state.json (written into the vault
-- by AioBridgeReceiver) for the exact, authoritative name/position/folded
-- state of everything AIO knows about -- including its own built-ins, not
-- just this app's scripts -- before scripting further changes.
--
-- Scope: this only rearranges widgets/scripts AIO already knows about (an
-- import still needs one human tap in AIO Store's Add Script picker — no
-- API imports a brand-new script). It's a placement/composition surface,
-- not a way to smuggle in unreviewed code.

local REPORT_ACTION = "com.obsidianwidget.aio.ACTION_REPORT"
local REPORT_RECEIVER = "com.obsidianwidget/com.obsidianwidget.AioBridgeReceiver"

local last_status = "Controller ready"

local function json_escape(s)
    return tostring(s):gsub('[\\"]', '\\%0'):gsub('\n', '\\n')
end

-- No json.encode dependency: available_widgets()/active_widgets() tables
-- have a known flat shape, so a small hand-rolled encoder avoids pulling in
-- the full json.lua module just for this.
local function encode_widget_list(list)
    local parts = {}
    for _, w in ipairs(list or {}) do
        table.insert(parts, string.format(
            '{"name":"%s","label":"%s","type":"%s","position":%s,"folded":%s,"enabled":%s,"clonable":%s}',
            json_escape(w.name or ""),
            json_escape(w.label or ""),
            json_escape(w.type or ""),
            w.position ~= nil and tostring(w.position) or "null",
            tostring(w.folded == true),
            tostring(w.enabled == true),
            tostring(w.clonable == true)
        ))
    end
    return "[" .. table.concat(parts, ",") .. "]"
end

local function report()
    local ok, payload = pcall(function()
        local available = aio:available_widgets() or {}
        local active = aio:active_widgets() or {}
        return string.format(
            '{"available":%s,"active":%s,"last_status":"%s"}',
            encode_widget_list(available),
            encode_widget_list(active),
            json_escape(last_status)
        )
    end)
    if not ok then
        payload = string.format('{"error":"%s","last_status":"%s"}',
            json_escape(payload), json_escape(last_status))
    end
    intent:send_broadcast{
        action = REPORT_ACTION,
        component = REPORT_RECEIVER,
        extras = { payload = payload },
    }
end

function on_command(cmd)
    local parts = cmd:split("=:=")
    local op = parts[1]
    local name = parts[2]
    local arg = parts[3]

    local ok, err = pcall(function()
        if op == "add" then
            aio:add_widget(name, arg ~= nil and arg ~= "" and tonumber(arg) or nil)
        elseif op == "remove" then
            aio:remove_widget(name)
        elseif op == "move" then
            aio:move_widget(name, tonumber(arg))
        elseif op == "fold" then
            aio:fold_widget(name, arg == "true")
        elseif op == "list" then
            -- no-op; report() below covers it
        else
            error("unknown op: " .. tostring(op))
        end
    end)

    last_status = ok and (tostring(op) .. " " .. tostring(name) .. " ok")
        or ("FAILED: " .. tostring(err))
    ui:show_text(last_status)
    report()
end

-- Keeps the state file honest for changes made by hand too (long-press
-- add/remove/move on the real home screen), not just via on_command.
function on_widget_action(action, name)
    last_status = tostring(action) .. " " .. tostring(name) .. " (manual)"
    ui:show_text(last_status)
    report()
end

-- A widget stays on AIO's loading placeholder forever until something
-- actually calls a ui:show_* function -- report() alone (just a broadcast,
-- no UI call) never did, which was the "stuck on loading" bug.
function on_load()
    ui:show_text(last_status)
    report()
end
function on_resume()
    ui:show_text(last_status)
    report()
end
function on_click() ui:show_text(last_status) end
