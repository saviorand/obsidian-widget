-- name = "Content Slot 3"
-- description = "Interchangeable vault-content widget (any note, checklist or plain text) -- one of several identical slots, most left folded until the agent points one at something"
-- type = "widget"
-- version = "1.0"
-- aio_version = "7.5.0-beta2"
-- uses_app = "com.obsidianwidget"

-- Deliberately scoped for v1: tapping the title opens the full editor
-- (widget_edit's click target, not widget_date's — the real widget keeps
-- those separate: title deep-links to Obsidian, the pencil opens our
-- editor). Settings and the cycle-note arrow aren't wired yet — the agent
-- can already reconfigure a note widget via ACTION_CONFIGURE broadcasts,
-- which covers most of what Settings would do here anyway.

local prefs = require "prefs"

local PROVIDER = "com.obsidianwidget/com.obsidianwidget.ObsidianWidgetProvider"
local current_bridge = nil
local edit_target = nil
local row_targets = {}
local row_labels = {}
local pending_refresh_ticks = nil

local function setup_widget()
    local id = widgets:setup(PROVIDER)
    if id == nil then return false end
    prefs.note_widget_id = id
    return true
end

local function refresh()
    if prefs.note_widget_id == nil or not widgets:bound(prefs.note_widget_id) then
        if not setup_widget() then
            ui:show_text("Can't bind Note widget")
            return
        end
    end
    -- Same reasoning as the Notes wrapper: force a real re-read (the note
    -- may have changed in Obsidian directly) before asking AIO to re-render
    -- whatever the app last pushed. send_broadcast is fire-and-forget, so
    -- request_updates() called immediately after it can win the race and
    -- re-render the OLD content before our own ACTION_REFRESH handler has
    -- finished its SAF read + updateAppWidget(). Ask once right away (cheap,
    -- covers the common case) and again after a couple of on_tick()s to
    -- pick up the post-refresh content once it's actually landed.
    intent:send_broadcast{
        action = "com.obsidianwidget.ACTION_REFRESH",
        component = "com.obsidianwidget/com.obsidianwidget.ObsidianWidgetProvider",
    }
    widgets:request_updates(prefs.note_widget_id)
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
        if prefs.note_widget_id ~= nil and widgets:bound(prefs.note_widget_id) then
            widgets:request_updates(prefs.note_widget_id)
        end
    end
end

function on_app_widget_updated(bridge)
    local ok, snapshot = pcall(function() return bridge:snapshot() end)
    if not ok or snapshot == nil then
        ui:show_text("Note widget unavailable")
        current_bridge = nil
        return
    end

    current_bridge = bridge
    edit_target = nil
    row_targets = {}
    row_labels = {}

    local title = "Note"
    local plain_text = nil
    local targets, texts, checked = {}, {}, {}

    for _, node in ipairs(snapshot.nodes or {}) do
        if node.resource_id == "com.obsidianwidget:id/widget_date" and node.text and node.text ~= "" then
            title = node.text
        elseif node.resource_id == "com.obsidianwidget:id/widget_edit" then
            edit_target = node.click_target
        elseif node.resource_id == "com.obsidianwidget:id/checklist_item_root" then
            table.insert(targets, node.click_target)
            table.insert(texts, "")
            table.insert(checked, false)
        elseif node.resource_id == "com.obsidianwidget:id/checklist_text" and #texts > 0 then
            texts[#texts] = node.text or ""
        elseif node.resource_id == "com.obsidianwidget:id/checklist_checkbox_mark" and #checked > 0 then
            if node.visible == true then checked[#checked] = true end
        elseif node.resource_id == "com.obsidianwidget:id/widget_note_preview" and node.text and node.text ~= "" then
            plain_text = node.text
        end
    end

    -- Prefer checklist rows when any exist — the real widget never shows
    -- both a populated checklist and the plain preview at once.
    for i, text in ipairs(texts) do
        row_targets[i] = targets[i]
        row_labels[i] = (checked[i] and "☑ " or "☐ ") .. text
    end

    local lines = { "<b>" .. title .. "</b>" }
    if #row_labels > 0 then
        for _, label in ipairs(row_labels) do table.insert(lines, label) end
    elseif plain_text ~= nil then
        table.insert(lines, plain_text)
    else
        table.insert(lines, "(empty)")
    end
    ui:show_lines(lines)
end

function on_click(idx)
    if current_bridge == nil then return end
    if idx == 1 then
        if edit_target ~= nil then current_bridge:click_handle(edit_target) end
        return
    end
    local row = idx - 1
    if row_targets[row] ~= nil then
        current_bridge:click_handle(row_targets[row])
    end
end
