-- name = "Panel 2"
-- description = "Fully agent-programmable content panel -- text, charts, or a full declarative layout"
-- type = "widget"
-- version = "2.0"
-- aio_version = "7.5.0-beta2"
-- uses_app = "com.obsidianwidget"

-- No native widget binding at all -- unlike obsidian-note.lua, this panel
-- doesn't read any specific vault file itself. The agent already has
-- direct filesystem access to the vault, so it computes whatever should be
-- shown and pushes the finished result here in one broadcast. Three modes,
-- selected by the first line of the pushed command:
--
-- IMPORTANT: the identifier AIO routes on_command by is the script's
-- literal FILENAME including the ".lua" extension -- "obsidian-panel-2.lua"
-- below, not "Panel 1" (confirmed on-device; contradicts what AIO's own
-- samples/tasker-widget-control.lua implies). Same identifier
-- obsidian-controller.lua's add/remove/move/fold ops expect.
--
-- TEXT (default, no prefix) -- title + body lines, HTML tags allowed:
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd "script:obsidian-panel-2.lua:<title>
-- <body line 1>
-- <body line 2>"
--   A leading "@<vault-relative-path>" line makes the whole panel tappable
--   (opens that note in the real editor).
--
-- CHART -- a real line chart (AIO's ui:show_chart), JSON on the rest of
-- the line after "chart:":
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd 'script:obsidian-panel-2.lua:chart:{"points":[[1700000000000,3],[1700086400000,5]],"format":"x:date y:number","title":"Tasks done","show_grid":true}'
--   points are [timestamp_ms, value] pairs; format/title/show_grid optional.
--
-- LAYOUT -- AIO's full declarative rich-UI element language (see
-- README_RICH_UI.md in ~/aiolauncher_scripts): any text/button/icon/
-- progress element, sized, colored, positioned -- as close to "any
-- layout" as this platform gets without a live web renderer (RemoteViews-
-- style Android widgets and AIO's own sandbox both exclude WebView, so
-- literal React/Ant Design can't run live here; this is AIO's own
-- equivalent expressive layer instead). JSON array of element tuples,
-- passed through to `gui{}` almost verbatim -- each JSON array becomes a
-- Lua table, so a spec written to match the gui{} examples in
-- README_RICH_UI.md works with no translation:
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd 'script:obsidian-panel-2.lua:layout:{"elements":[["text","<b>Tasks</b>",{"size":20}],["new_line",2],["progress","Today",{"progress":70}],["new_line",2],["button","Open Todo",{"color":"#00aa00"}]],"actions":{"3":"@Todo.md"}}'
--   "actions" maps a 1-based element index (matching on_click's argument)
--   to "@<vault-relative-path>" to open on tap -- only elements you list
--   there are clickable in a way that does anything.
--
-- "clear" resets to the placeholder text state.
--
-- (--es values can contain literal newlines -- construct the string with
-- $'...' or a heredoc, not two-character "\n" escapes; JSON values are
-- single-line and don't need this.)

local prefs = require "prefs"
local json = require "json"

local click_actions = {}

local function split_lines(s)
    local lines = {}
    for line in (s .. "\n"):gmatch("(.-)\n") do
        table.insert(lines, line)
    end
    return lines
end

local function open_note(path)
    intent:send_broadcast{
        action = "com.obsidianwidget.ACTION_EDIT",
        component = "com.obsidianwidget/com.obsidianwidget.ObsidianWidgetProvider",
        extras = { note_path = path },
    }
end

local function render_text()
    local title = prefs.panel_title or "Panel"
    local body = prefs.panel_body or "(nothing pushed yet)"
    local lines = { "<b>" .. title .. "</b>" }
    for _, l in ipairs(split_lines(body)) do
        table.insert(lines, l)
    end
    ui:show_lines(lines)
end

local function render_chart()
    local ok, spec = pcall(json.decode, prefs.panel_payload or "{}")
    if not ok or spec == nil or spec.points == nil then
        ui:show_text("Bad chart data")
        return
    end
    ui:show_chart(spec.points, spec.format, spec.title, spec.show_grid)
end

local function render_layout()
    local ok, spec = pcall(json.decode, prefs.panel_payload or "{}")
    if not ok or spec == nil or spec.elements == nil then
        ui:show_text("Bad layout data")
        return
    end
    click_actions = spec.actions or {}
    local ok2, built = pcall(gui, spec.elements)
    if not ok2 then
        ui:show_text("Layout error: " .. tostring(built))
        return
    end
    built.render()
end

local function render()
    local mode = prefs.panel_mode or "text"
    if mode == "chart" then render_chart()
    elseif mode == "layout" then render_layout()
    else render_text() end
end

function on_command(cmd)
    if cmd == "clear" then
        prefs.panel_mode = "text"
        prefs.panel_title = nil
        prefs.panel_body = nil
        prefs.panel_link = nil
        prefs.panel_payload = nil
        click_actions = {}
        render()
        return
    end

    if cmd:starts_with("chart:") then
        prefs.panel_mode = "chart"
        prefs.panel_payload = cmd:sub(("chart:"):len() + 1)
        render()
        return
    end

    if cmd:starts_with("layout:") then
        prefs.panel_mode = "layout"
        prefs.panel_payload = cmd:sub(("layout:"):len() + 1)
        render()
        return
    end

    -- Default: text mode.
    prefs.panel_mode = "text"
    local lines = split_lines(cmd)
    local i = 1
    local link = nil
    if lines[1] and lines[1]:starts_with("@") then
        link = lines[1]:sub(2)
        i = 2
    end
    prefs.panel_link = link
    prefs.panel_title = lines[i] or "Panel"
    local body_lines = {}
    for j = i + 1, #lines do
        table.insert(body_lines, lines[j])
    end
    prefs.panel_body = table.concat(body_lines, "\n")
    render()
end

function on_load() render() end
function on_resume() render() end

function on_click(idx)
    local mode = prefs.panel_mode or "text"
    if mode == "text" then
        local link = prefs.panel_link
        if link ~= nil and link ~= "" then open_note(link) end
        return
    end
    if mode == "layout" and idx ~= nil then
        local action = click_actions[tostring(idx)]
        if action ~= nil and action:starts_with("@") then
            open_note(action:sub(2))
        end
    end
end
