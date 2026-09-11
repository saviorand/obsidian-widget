-- name = "Agent Search Chat"
-- description = "Talk to the Claude Code agent from AIO's search bar"
-- type = "search"
-- version = "1.0"
-- aio_version = "7.5.0-beta2"

-- EXPERIMENTAL: AIO's search chat mode (search:chat_start/on_chat) expects
-- on_chat() to return the reply synchronously, but talking to the real
-- agent (agent-host.mjs -> claude -p) is a multi-second async HTTP call
-- (http:post(), since AIO's Lua sandbox has no blocking network call and
-- no documented way to append a message into an already-open chat
-- session). This tries calling search:chat_start() again from
-- on_network_result() once the real reply arrives, to see whether that
-- updates the same conversation or resets it -- unconfirmed until tested
-- on-device. If it resets/loses context each turn, this needs a
-- different design (e.g. redirect to the Agent widget for the real
-- reply instead of trying to keep the conversation live here).
--
-- Needs agent-host.mjs's one-shot HTTP endpoint (added alongside the
-- existing WebSocket protocol specifically for this -- AIO's http:
-- module can't hold a socket open the way the chat widget's WebSocket
-- does): POST http://127.0.0.1:8178/chat {"prompt":"...","sessionId":?}
-- -> {"reply":"...","sessionId":"..."} once the turn completes.
-- agent-host.mjs must be running (~/agent-host/control.sh start).

local prefs = require "prefs"
local json = require "json"

local CHAT_URL = "http://127.0.0.1:8178/chat"
local last_query = ""

local function send_prompt(text)
    local ok, body = pcall(json.encode, { prompt = text, sessionId = prefs.chat_session_id })
    if ok then
        http:post(CHAT_URL, body, "application/json")
    end
end

function on_search(query)
    last_query = query
    search:show_buttons({ "Chat with Agent" })
end

function on_click(idx)
    if last_query ~= nil and last_query ~= "" then
        -- The text typed into the search bar before tapping this button is
        -- the first prompt -- chat_start's initial_message is shown as an
        -- assistant line, so this is the closest available "acknowledged,
        -- working on it" cue until the real reply lands.
        search:chat_start("Agent", "Thinking...", "fa:comment")
        send_prompt(last_query)
    else
        search:chat_start("Agent", "Ask me anything about your vault or home screen.", "fa:comment")
    end
    return false
end

local function last_user_message(messages)
    for i = #messages, 1, -1 do
        local msg = messages[i]
        if msg and msg.role == "user" then
            return msg.content or ""
        end
    end
    return ""
end

function on_chat(messages)
    local text = last_user_message(messages)
    if text == "" then return "..." end
    if text == "exit" then
        search:chat_stop()
        return
    end
    send_prompt(text)
    return "Thinking..."
end

function on_network_result(body, code, headers)
    local ok, resp = pcall(json.decode, body or "")
    if not ok or type(resp) ~= "table" then
        search:chat_start("Agent", "Bad response from agent host (code " .. tostring(code) .. ")", "fa:comment")
        return
    end
    if resp.sessionId then prefs.chat_session_id = resp.sessionId end
    search:chat_start("Agent", resp.reply or resp.error or "(no reply)", "fa:comment")
end

function on_network_error(error_message)
    search:chat_start(
        "Agent",
        "Connection error: " .. tostring(error_message) ..
        " -- is agent-host running? (~/agent-host/control.sh start)",
        "fa:comment"
    )
end
