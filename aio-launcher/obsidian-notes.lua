-- name = "Notes"
-- description = "AIO wrapper for the obsidian-widget notes browser"
-- type = "widget"
-- version = "1.0"
-- aio_version = "7.5.0-beta2"
-- uses_app = "com.obsidianwidget"

local prefs = require "prefs"

local PROVIDER = "com.obsidianwidget/com.obsidianwidget.NotesWidgetProvider"
local current_bridge = nil
local new_note_target = nil
local row_targets = {}
local row_labels = {}
local pending_refresh_ticks = nil

local function setup_widget()
    local id = widgets:setup(PROVIDER)
    if id == nil then return false end
    prefs.notes_widget_id = id
    return true
end

local function refresh()
    if prefs.notes_widget_id == nil or not widgets:bound(prefs.notes_widget_id) then
        if not setup_widget() then
            ui:show_text("Can't bind Notes widget")
            return
        end
    end
    -- widgets:request_updates() alone re-delivers whatever RemoteViews the
    -- app last pushed — it doesn't ask the provider to regenerate, so an
    -- edit made outside the app (in Obsidian, or from the app's own real
    -- widget) wouldn't show up here otherwise. Force a real re-read first.
    -- send_broadcast is fire-and-forget though, so the immediate
    -- request_updates() below can win the race and re-render stale content
    -- before our ACTION_REFRESH handler's SAF read + updateAppWidget() has
    -- actually finished. Ask again after a couple of on_tick()s to catch
    -- the real post-refresh content.
    intent:send_broadcast{
        action = "com.obsidianwidget.notes.ACTION_REFRESH",
        component = "com.obsidianwidget/com.obsidianwidget.NotesWidgetProvider",
    }
    widgets:request_updates(prefs.notes_widget_id)
    pending_refresh_ticks = 2
end

function on_load() refresh() end
function on_resume() refresh() end
function on_alarm() refresh() end

function on_tick()
    if pending_refresh_ticks == nil then return end
    pending_refresh_ticks = pending_refresh_ticks - 1
    if pending_refresh_ticks <= 0 then
        pending_refresh_ticks = nil
        if prefs.notes_widget_id ~= nil and widgets:bound(prefs.notes_widget_id) then
            widgets:request_updates(prefs.notes_widget_id)
        end
    end
end

function on_app_widget_updated(bridge)
    local ok, snapshot = pcall(function() return bridge:snapshot() end)
    if not ok or snapshot == nil then
        ui:show_text("Notes widget unavailable")
        current_bridge = nil
        return
    end

    current_bridge = bridge
    new_note_target = nil
    row_targets = {}
    row_labels = {}

    -- Rows repeat as notes_item_root (carries the row's click target) then
    -- its own notes_item_name/notes_item_folder text children, in that
    -- document order — confirmed against a live capture.
    local pending_label = nil
    local pending_target = nil
    local have_pending = false

    local function flush()
        if have_pending then
            table.insert(row_targets, pending_target)
            table.insert(row_labels, pending_label or "(untitled)")
        end
    end

    for _, node in ipairs(snapshot.nodes or {}) do
        if node.resource_id == "com.obsidianwidget:id/notes_widget_new" then
            new_note_target = node.click_target
        elseif node.resource_id == "com.obsidianwidget:id/notes_item_root" then
            flush()
            pending_target = node.click_target
            pending_label = nil
            have_pending = true
        elseif node.resource_id == "com.obsidianwidget:id/notes_item_name" and have_pending then
            pending_label = node.text
        elseif node.resource_id == "com.obsidianwidget:id/notes_item_folder" and have_pending then
            if node.text and node.text ~= "" then
                pending_label = (pending_label or "") .. "  (" .. node.text .. ")"
            end
        end
    end
    flush()

    local lines = { "+ New note" }
    if #row_labels == 0 then
        table.insert(lines, "(no notes found)")
    else
        for _, label in ipairs(row_labels) do
            table.insert(lines, label)
        end
    end
    ui:show_lines(lines)
end

function on_click(idx)
    if current_bridge == nil then return end
    if idx == 1 then
        if new_note_target ~= nil then current_bridge:click_handle(new_note_target) end
        return
    end
    local row = idx - 1
    if row_targets[row] ~= nil then
        current_bridge:click_handle(row_targets[row])
    end
end
