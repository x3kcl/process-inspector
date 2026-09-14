#!/usr/bin/env bash
# .video/raw/<clip>.webm  +  .video/title/<clip>.png  ->  videos/<clip>.mp4
#
# PowerPoint plays H.264/AAC in an .mp4 container; it does NOT play the VP8/WebM that
# Playwright records, and Playwright's own bundled ffmpeg is a cut-down build with libvpx
# only (no libx264) — hence the containerised full ffmpeg.
#
# Each clip is prefixed with its title card so the poster frame PowerPoint shows before you
# press play is a deliberate caption rather than a black or mid-scroll frame. A silent AAC
# track is muxed in: some PowerPoint builds refuse to play a video-only mp4.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RAW="$HERE/.video/raw"; TITLE="$HERE/.video/title"; POSTER="$HERE/.video/poster"
OUT="$HERE/videos"
TITLE_SECONDS="${TITLE_SECONDS:-2.2}"
mkdir -p "$OUT" "$POSTER"

ff() { docker run --rm -v "$HERE":/w -w /w --entrypoint /usr/local/bin/ffmpeg \
        linuxserver/ffmpeg:latest -hide_banner -loglevel error "$@"; }

for src in "$RAW"/*.webm; do
  name="$(basename "$src" .webm)"
  card="$TITLE/$name.png"
  [ -f "$card" ] || { echo "!! no title card for $name — skipping" >&2; continue; }
  echo "encoding $name ..."
  ff -y -loop 1 -t "$TITLE_SECONDS" -i ".video/title/$name.png" \
        -i ".video/raw/$name.webm" \
        -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 \
     -filter_complex "[0:v]scale=1600:900,fps=25,format=yuv420p,setsar=1[t]; \
                      [1:v]scale=1600:900,fps=25,format=yuv420p,setsar=1[c]; \
                      [t][c]concat=n=2:v=1:a=0[v]" \
     -map "[v]" -map 2:a -shortest \
     -c:v libx264 -preset slow -crf 22 -profile:v high -level 4.1 -pix_fmt yuv420p \
     -c:a aac -b:a 48k -movflags +faststart \
     "videos/$name.mp4"
done

# Cover images for the deck: one representative UI frame per clip, at the second named in
# clips.mjs. build-deck.mjs feeds these to addMedia({cover}) — the clip's own title card
# would just duplicate the slide header.
echo
node -e '
  import("./clips.mjs").then(({CLIPS}) =>
    console.log(CLIPS.map(c => c.name + " " + (c.poster ?? 10)).join("\n")))
' | while read -r name at; do
  echo "poster $name @ ${at}s"
  ff -y -ss "$at" -i ".video/raw/$name.webm" -frames:v 1 -q:v 2 ".video/poster/$name.png"
done

echo
ls -la "$OUT"
echo
for f in "$OUT"/*.mp4; do
  printf '%-28s %s\n' "$(basename "$f")" \
    "$(ff -i "videos/$(basename "$f")" 2>&1 | grep -E 'Duration' | head -1 | sed 's/^ *//')"
done
