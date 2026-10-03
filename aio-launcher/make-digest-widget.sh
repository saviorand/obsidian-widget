#!/bin/sh
# Regenerates obsidian-digest-widget.lua from obsidian-query-widget.lua: same
# code, its own name/description, so AIO treats it as a separate script.
cd "$(dirname "$0")"
awk 'NR==1{print "-- name = \"Obsidian Digest\""; next}
     NR==2{print "-- description = \"Daily digest summary (copy of obsidian-query-widget.lua)\""; next}
     !done && !/^-- [a-z_]+ = /{
       print "-- GENERATED copy of obsidian-query-widget.lua, so the digest is a second,"
       print "-- independent widget (AIO scripts aren'"'"'t clonable). Its default query"
       print "-- (\"digest\") and cache file names come from this file'"'"'s own name."
       print "-- Regenerate after changing the original: bash make-digest-widget.sh"
       done=1}
     {print}' obsidian-query-widget.lua > obsidian-digest-widget.lua
