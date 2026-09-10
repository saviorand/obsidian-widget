-- name = "Agent"
-- description = "AIO wrapper for the obsidian-widget Agent chat glance"
-- type = "widget"
-- version = "1.0"
-- aio_version = "7.5.0-beta2"
-- uses_app = "com.obsidianwidget"

local prefs = require "prefs"
local fmt = require "fmt"

local PROVIDER = "com.obsidianwidget/com.obsidianwidget.AgentWidgetProvider"
local current_bridge = nil
local current_click_target = nil

local function setup_widget()
    local id = widgets:setup(PROVIDER)
    if id == nil then return false end
    prefs.agent_widget_id = id
    return true
end

local function refresh()
    if prefs.agent_widget_id == nil or not widgets:bound(prefs.agent_widget_id) then
        if not setup_widget() then
            ui:show_text("Can't bind Agent widget")
            return
        end
    end
    widgets:request_updates(prefs.agent_widget_id)
end

function on_load() refresh() end
function on_resume() refresh() end
function on_alarm() refresh() end

function on_app_widget_updated(bridge)
    local ok, snapshot = pcall(function() return bridge:snapshot() end)
    if not ok or snapshot == nil then
        ui:show_text("Agent widget unavailable")
        current_bridge = nil
        current_click_target = nil
        return
    end

    local title, preview, click_target = "Agent", "Tap to ask…", nil
    for _, node in ipairs(snapshot.nodes or {}) do
        if node.resource_id == "com.obsidianwidget:id/agent_widget_title" and node.text then
            title = node.text
        elseif node.resource_id == "com.obsidianwidget:id/agent_widget_preview" and node.text and node.text ~= "" then
            preview = node.text
        end
        if node.click_target ~= nil and click_target == nil then
            click_target = node.click_target
        end
    end

    current_bridge = bridge
    current_click_target = click_target

    ui:show_text("<b>" .. title .. "</b><br/>" .. fmt.secondary(preview))
end

function on_click(idx)
    if current_bridge ~= nil and current_click_target ~= nil then
        current_bridge:click_handle(current_click_target)
    end
end
