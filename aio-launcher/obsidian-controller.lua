-- name = "Obsidian Controller"
-- description = "Headless add/remove/move/fold control for the home screen, driven by am broadcast"
-- type = "widget"
-- version = "1.0"
-- aio_version = "7.5.0-beta2"

-- One-time setup this script can't do for itself:
--   AIO Settings -> Tasker -> Remote API -> enable it, note the password.
-- (This is AIO's own remote-control gate, unrelated to the real Tasker app —
-- no Tasker install needed to use it this way.)
--
-- Command shape (matches AIO's own samples/tasker-widget-control.lua):
--
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd "script:obsidian controller:<op>=:=<name>=:=<arg>" \
--     --es password <remote-api-password>
--
-- Ops:
--   add=:=<widget-name>=:=[position]   add an already-imported widget/script
--   remove=:=<widget-name>=:=          remove it from the screen
--   move=:=<widget-name>=:=<position>  reorder it
--   fold=:=<widget-name>=:=<true|false>
--   list=:=                            no-op; just refreshes the report below
--
-- <widget-name> is aio:available_widgets()'s internal `name` field, not the
-- display title — run `list` once and read .aio-layout-state.json (written
-- into the vault by AioBridgeReceiver) to find the exact names on this
-- device before scripting further changes. That file also lists every
-- widget already available to add, including AIO's own built-ins, not just
-- this app's three wrappers.
--
-- Scope: this only rearranges widgets/scripts AIO already knows about (an
-- import still needs one human tap in AIO Store's Add Script picker — no
-- API imports a brand-new script). It's a placement/composition surface,
-- not a way to smuggle in unreviewed code.

local REPORT_ACTION = "com.obsidianwidget.aio.ACTION_REPORT"
local REPORT_RECEIVER = "com.obsidianwidget/.AioBridgeReceiver"

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
    report()
end

function on_load() report() end
function on_resume() report() end
function on_click() ui:show_text(last_status) end
