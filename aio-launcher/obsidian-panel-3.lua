-- name = "Panel 3"
-- description = "Fully agent-programmable content panel -- text, charts, or a full declarative layout, with a general tap-action vocabulary"
-- type = "widget"
-- version = "3.0"
-- aio_version = "7.5.0-beta2"
-- uses_app = "com.obsidianwidget"
-- on_resume_when_folding = "true"

-- No native widget binding at all -- unlike obsidian-note.lua, this panel
-- doesn't read any specific vault file itself, and doesn't go through
-- AIO's widgets:setup()/bridge:snapshot() at all (confirmed unreliable
-- beyond the very first widget ever bound in an AIO session -- see
-- aio-launcher/README.md's "Content slots" section). The agent already
-- has direct filesystem access to the vault, so it computes whatever
-- should be shown and pushes the finished result here in one broadcast.
--
-- ONE JSON envelope for everything (v3 -- replaces the old chart:/layout:
-- text-prefix protocol):
--
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd '{"mode":"text","title":"...","body":"line1\nline2","action":"open:Todo.md"}'
--
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd '{"mode":"chart","points":[[ts_ms,value],...],"format":"x:date y:number","title":"...","show_grid":true,"action":"open:X.md"}'
--
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd '{"mode":"layout","elements":[["text","<b>Tasks</b>",{"size":20}],["new_line",2],["button","Open Todo",{"color":"#00aa00"}]],"actions":{"2":"open:Todo.md"}}'
--
--   am broadcast -a ru.execbit.aiolauncher.COMMAND --es cmd '{"mode":"clear"}'
--
-- (Real script name goes after "script:" in the actual cmd extra --
-- "script:obsidian-panel-3.lua:<json>" -- shortened above for space; see
-- the vault's CLAUDE.md for the full command shape. AIO routes by the
-- script's literal FILENAME including ".lua", confirmed on-device --
-- not the "-- name =" title.)
--
-- LAYOUT mode notes: `elements` passes straight into AIO's `gui{}`
-- (README_RICH_UI.md in ~/aiolauncher_scripts) -- a JSON array of tuples
-- is exactly the Lua table shape it expects. ALWAYS give every tuple an
-- explicit options table, even `{}` -- `["text","...",{}]`, never
-- `["text","..."]` -- confirmed on-device that omitting it throws a LuaJ
-- coercion error for a JSON-decoded tuple, even though AIO's own docs
-- show the bare 2-item form as valid (only true for a native Lua literal).
--
-- ACTION VOCABULARY -- text/chart get one `action` (the whole panel is
-- the tap target); layout gets `actions`, a map from 1-based element
-- index (matching on_click's argument) to the same verb:arg strings:
--
--   open:<vault-relative-path>       opens that note in the real editor
--   broadcast:<json>                 sends an arbitrary Android broadcast,
--                                    json = {"action":"...","component":"...",
--                                    "extras":{...}} (component optional)
--   agent:<free text>                forwards the text as a fresh prompt
--                                    to agent-host.mjs (the same daemon
--                                    the chat widget uses) via
--                                    AgentTriggerReceiver -- for anything
--                                    needing real judgment rather than a
--                                    fixed effect. Fire-and-forget: the
--                                    agent's own actions (editing a file,
--                                    pushing updated panel content) are
--                                    the visible result, not a reply here.
--
-- (--es values can contain literal newlines for body text -- construct
-- the string with $'...' or a heredoc, not two-character "\n" escapes.)

local prefs = require "prefs"
local json = require "json"

local click_actions = {}
-- text/chart modes have exactly one tap target for the WHOLE panel,
-- regardless of which rendered line was tapped (ui:show_lines()/
-- show_chart() don't give per-line click indices the way gui{} does) --
-- kept separate from click_actions (layout mode's per-element map) so a
-- tap on any line of a multi-line text panel still fires the one action.
local mode_action = nil

local function open_note(path)
    intent:send_broadcast{
        action = "com.obsidianwidget.ACTION_EDIT",
        component = "com.obsidianwidget/com.obsidianwidget.ObsidianWidgetProvider",
        extras = { note_path = path },
    }
end

local function send_broadcast_action(arg)
    local ok, spec = pcall(json.decode, arg)
    if not ok or type(spec) ~= "table" or spec.action == nil then return end
    intent:send_broadcast{
        action = spec.action,
        component = spec.component,
        extras = spec.extras or {},
    }
end

local function send_agent_signal(text)
    intent:send_broadcast{
        action = "com.obsidianwidget.aio.ACTION_AGENT_SIGNAL",
        component = "com.obsidianwidget/com.obsidianwidget.AgentTriggerReceiver",
        extras = { payload = text, panel = aio:self_name() },
    }
end

local function dispatch_action(action)
    if action == nil or action == "" then return end
    local verb, rest = action:match("^([%a_]+):(.*)$")
    if verb == "open" then
        open_note(rest)
    elseif verb == "broadcast" then
        send_broadcast_action(rest)
    elseif verb == "agent" then
        send_agent_signal(rest)
    end
end

local function split_lines(s)
    local lines = {}
    for line in (s .. "\n"):gmatch("(.-)\n") do
        table.insert(lines, line)
    end
    return lines
end

local function render_text(spec)
    local title = spec.title or "Panel"
    local lines = { "<b>" .. title .. "</b>" }
    for _, l in ipairs(split_lines(spec.body or "")) do
        table.insert(lines, l)
    end
    ui:show_lines(lines)
end

local function render_chart(spec)
    if spec.points == nil then
        ui:show_text("Bad chart data")
        return
    end
    ui:show_chart(spec.points, spec.format, spec.title, spec.show_grid)
end

local function render_layout(spec)
    if spec.elements == nil then
        ui:show_text("Bad layout data")
        return
    end
    click_actions = spec.actions or {}
    local ok, built = pcall(gui, spec.elements)
    if not ok then
        ui:show_text("Layout error: " .. tostring(built))
        return
    end
    built.render()
end

local function render()
    -- chart/layout rendering doesn't go through AIO's line-based ui
    -- module, so it doesn't participate in the launcher's automatic
    -- "show first line when folded" behavior the way ui:show_lines() does
    -- -- fold alone left the full chart/layout visible.
    -- on_resume_when_folding (metadata above) makes on_resume fire on
    -- every fold/unfold, so render explicitly nothing here when folded.
    if ui:is_folded() then
        ui:show_text("")
        return
    end

    local raw = prefs.panel_spec
    if raw == nil then
        mode_action = nil
        click_actions = {}
        ui:show_text("(nothing pushed yet)")
        return
    end
    local ok, spec = pcall(json.decode, raw)
    if not ok or type(spec) ~= "table" then
        mode_action = nil
        click_actions = {}
        ui:show_text("Bad panel spec")
        return
    end

    if spec.mode == "layout" then
        mode_action = nil
        render_layout(spec)
    elseif spec.mode == "chart" then
        mode_action = spec.action
        click_actions = {}
        render_chart(spec)
    else
        mode_action = spec.action
        click_actions = {}
        render_text(spec)
    end
end

function on_command(cmd)
    local ok, spec = pcall(json.decode, cmd)
    if not ok or type(spec) ~= "table" then
        ui:show_text("Bad command (expected JSON)")
        return
    end
    if spec.mode == "clear" then
        prefs.panel_spec = nil
        render()
        return
    end
    prefs.panel_spec = cmd
    render()
end

function on_load() render() end
function on_resume() render() end

function on_click(idx)
    if mode_action ~= nil then
        dispatch_action(mode_action)
        return
    end
    dispatch_action(click_actions[tostring(idx or 1)])
end
