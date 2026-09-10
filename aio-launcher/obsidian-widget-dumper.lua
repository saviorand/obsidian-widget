-- name = "Obsidian widget dumper"
-- description = "Test: can AIO bind and inspect com.obsidianwidget's widgets?"
-- type = "widget"
-- aio_version = "7.5.0-beta2"
-- foldable = "false"

app_pkg = "com.obsidianwidget"

-- Widget size (string from "1x1" to "4x4")
widget_size = "4x2"

labels = {}
providers = {}
w_content = ""
wid = -1

function on_resume()
    local list = widgets:list(app_pkg)

    if list == nil then
        ui:show_text("Error: No widgets found for " .. app_pkg .. " — AIO can't see any AppWidgetProvider for this package.")
        return
    end

    labels = map(function(it) return it.label end, list)
    providers = map(function(it) return it.provider end, list)

    if wid < 0 then
        local lines = {"Found " .. #labels .. " widget(s):"}
        for _, l in ipairs(labels) do table.insert(lines, l) end
        table.insert(lines, "")
        table.insert(lines, "Tap a name below to bind it")
        ui:show_lines(lines)
    else
        widgets:request_updates(wid, widget_size)
    end
end

function on_click(idx)
    if w_content ~= "" then
        system:copy_to_clipboard(w_content)
        ui:show_toast("Copied dump to clipboard")
        return
    end
    -- idx 1 is the "Found N widget(s):" header line, so labels start at idx-1
    local label_idx = idx - 1
    if label_idx < 1 or label_idx > #providers then return end
    wid = widgets:setup(providers[label_idx])
    widgets:request_updates(wid, widget_size)
end

function on_app_widget_updated(bridge)
    local provider = bridge:provider()
    local dump = bridge:dump_tree()
    local colors = bridge:dump_colors()
    w_content = provider .. "\n\n" .. dump .. "\n\n" .. serialize(colors)

    -- snapshot_json() is newer than this AIO version has — skip it rather
    -- than crash; provider/dump_tree/dump_colors already answer the
    -- question we actually care about.
    local ok, snapshot = pcall(function() return bridge:snapshot_json() end)
    if ok and snapshot then
        files:write("obsidian-widget-snapshot.json", snapshot)
    end

    ui:show_text("%%txt%%" .. w_content .. "\n\n(tap to copy full dump to clipboard)")
    debug:log("dump:\n\n" .. w_content)
end
