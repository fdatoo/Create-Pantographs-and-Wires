#!/bin/sh
# Encodes a traction sound pack's WAV loops to Ogg Vorbis in place, for shipping in the jar.
#
# Packs are authored as 48 kHz WAV (see the pack README), which is far too large to ship: the WMATA pack
# is about 195 MB as WAV and 12.5 MB as Vorbis quality 10. The player reads .ogg first and .wav otherwise,
# so a pack can mix both. Vorbis keeps the exact sample count, which the loops need.
#
# Usage: tools/encode-traction-pack.sh <pack dir> [quality]   (needs oggenc; quality defaults to 10)
set -eu
pack=${1:?pack directory}
quality=${2:-10}
command -v oggenc >/dev/null || { echo "oggenc not found (brew install vorbis-tools)" >&2; exit 1; }

find "$pack" -name '*.wav' -not -path '*/previews/*' | while IFS= read -r wav; do
    ogg=${wav%.wav}.ogg
    oggenc -Q -q "$quality" -o "$ogg" "$wav" 2>/dev/null
    [ -s "$ogg" ] || { echo "encoding failed: $wav" >&2; exit 1; }
    rm "$wav"
done
echo "encoded $(find "$pack" -name '*.ogg' | wc -l | tr -d ' ') loops; pack is now $(du -sh "$pack" | cut -f1)"
