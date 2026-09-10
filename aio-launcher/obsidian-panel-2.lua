-- name = "Panel 2"
-- description = "Fully agent-programmable content panel -- push any text, the agent decides what's shown"
-- type = "widget"
-- version = "1.0"
-- aio_version = "7.5.0-beta2"
-- uses_app = "com.obsidianwidget"

-- No native widget binding at all -- unlike obsidian-note.lua, this panel
-- doesn't read any specific vault file itself. The agent already has
-- direct filesystem access to the vault (it's just reading local files
-- with its own tools), so it computes whatever should be shown -- a note's
-- content, a summary across several notes, a checklist it renders itself,
-- an agent-chat digest, anything -- and pushes the finished text here in
-- one broadcast. This panel has no opinion about what content is.
--
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd "script:panel 1:<title>
-- <body line 1>
-- <body line 2>
-- ..."
--
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd "script:panel 1:clear"
--
-- To make the whole panel tappable (opens the real note editor on that
-- vault-relative path), prefix with an "@" line:
--
--   am broadcast -a ru.execbit.aiolauncher.COMMAND \
--     --es cmd "script:panel 1:@Todo.md
-- Todo
-- - [ ] first item
-- - [x] second item"
--
-- (--es values can contain literal newlines -- construct the string with
-- $'...'  or a quoted heredoc, not string concatenation with "\n" as two
-- characters.) "clear" is the one reserved first line; anything else is
-- treated as real content starting from line 1 (or line 2, if line 1 is
-- an "@path" link marker).
--
-- Multiple panels (Panel 1-4) exist as separate instances -- most folded
-- via obsidian-controller.lua -- exactly like the note/content-slot
-- widgets, but each can show literally anything instead of only a bound
-- note's content. Use whichever fits: a content slot for a checklist you
-- want to tap individual items on, a panel for anything else.

local prefs = require "prefs"

local function split_lines(s)
    local lines = {}
    for line in (s .. "\n"):gmatch("(.-)\n") do
        table.insert(lines, line)
    end
    return lines
end

local function render()
    local title = prefs.panel_title or "Panel"
    local body = prefs.panel_body or "(nothing pushed yet)"
    local lines = { "<b>" .. title .. "</b>" }
    for _, l in ipairs(split_lines(body)) do
        table.insert(lines, l)
    end
    ui:show_lines(lines)
end

function on_command(cmd)
    if cmd == "clear" then
        prefs.panel_title = nil
        prefs.panel_body = nil
        prefs.panel_link = nil
        render()
        return
    end

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

function on_click()
    local link = prefs.panel_link
    if link ~= nil and link ~= "" then
        intent:send_broadcast{
            action = "com.obsidianwidget.ACTION_EDIT",
            component = "com.obsidianwidget/com.obsidianwidget.ObsidianWidgetProvider",
            extras = { note_path = link },
        }
    end
end
