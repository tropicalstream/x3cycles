#!/usr/bin/env python3
"""
Pre-generate Tempest 2000-style voice commentary with Fish Audio TTS.

The game plays these clips offline (the APK has no INTERNET permission);
this script is the only thing that talks to the network, and you run it
once per config change on your computer.

Usage:
    cp tools/fish.config.example tools/fish.config
    # edit tools/fish.config with your API key and voice model id
    python3 tools/generate_commentary.py            # generate missing clips
    python3 tools/generate_commentary.py --dry-run  # validate config only
    python3 tools/generate_commentary.py --force    # regenerate everything

Private config, not committed: tools/fish.config
    FISH_API_KEY             - Fish Audio API key
    DEFAULT_VOICE_MODEL_ID   - default Fish voice model/reference id
    VOICE_<NAME>_MODEL_ID    - optional named voice ids used by lines[]

Public config: app/src/main/assets/commentary.json
    lines[]                  - id, event, voice, quote (spoken), reason (why/when)

Output: app/src/main/assets/voice/<id>.mp3  ->  rebuild the APK after.
Clips are loudness-normalized (ffmpeg loudnorm, -16 LUFS) so different Fish
voice models never end up mismatched in volume. Needs ffmpeg on PATH;
falls back to raw (unnormalized) output with a printed note if it's missing.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request

# Different Fish voice models render at noticeably different source
# loudness (the MCP voice swap on 2026-07-19 came in ~10dB quieter than
# the previous one). Loudness-normalize every clip to a consistent target
# so voices/lines never fight each other in-game. Requires ffmpeg; if it's
# not on PATH, clips are written as-is (a printed note, not a silent gap —
# this is a human-run build tool, not runtime game code).
LOUDNORM_TARGET = "loudnorm=I=-16:TP=-1.5:LRA=11"


def normalize_audio(audio_bytes, fmt):
    if not shutil.which("ffmpeg"):
        return audio_bytes, False
    with tempfile.TemporaryDirectory() as td:
        src = os.path.join(td, f"in.{fmt}")
        dst = os.path.join(td, f"out.{fmt}")
        with open(src, "wb") as f:
            f.write(audio_bytes)
        result = subprocess.run(
            ["ffmpeg", "-y", "-i", src, "-af", LOUDNORM_TARGET, "-ar", "44100", dst],
            capture_output=True, timeout=60
        )
        if result.returncode != 0 or not os.path.exists(dst):
            return audio_bytes, False
        with open(dst, "rb") as f:
            return f.read(), True

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "app", "src", "main", "assets", "commentary.json")
PRIVATE_CONFIG = os.path.join(ROOT, "tools", "fish.config")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "voice")


def load_private_config():
    values = {}
    if not os.path.exists(PRIVATE_CONFIG):
        return values
    with open(PRIVATE_CONFIG, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def voice_key(name):
    return f"VOICE_{name.upper().replace('-', '_')}_MODEL_ID"


def resolve_model_id(line, private, fallback):
    explicit = line.get("voice_model_id", "").strip()
    if explicit and "PUT_YOUR" not in explicit:
        return explicit

    voice_name = line.get("voice", "").strip()
    if voice_name:
        named = private.get(voice_key(voice_name), "").strip()
        if named and "PUT_" not in named:
            return named

    default = (
        private.get("DEFAULT_VOICE_MODEL_ID", "").strip()
        or private.get("FISH_VOICE_MODEL_ID", "").strip()
    )
    if default and "PUT_" not in default:
        return default

    if fallback and "PUT_YOUR" not in fallback:
        return fallback
    return ""


def main():
    force = "--force" in sys.argv
    dry_run = "--dry-run" in sys.argv

    private = load_private_config()
    api_key = os.environ.get("FISH_API_KEY") or private.get("FISH_API_KEY")
    if not api_key and not dry_run:
        sys.exit("Set FISH_API_KEY in tools/fish.config or in the environment first.")

    with open(CONFIG, encoding="utf-8") as f:
        cfg = json.load(f)

    tts = cfg["fish_tts"]
    url = tts.get("api_url", "https://api.fish.audio/v1/tts")
    fmt = tts.get("format", "mp3")

    os.makedirs(OUT_DIR, exist_ok=True)
    done = skipped = failed = 0

    for line in cfg["lines"]:
        model_id = resolve_model_id(line, private, tts.get("voice_model_id", ""))
        if not model_id:
            sys.exit(
                f"No Fish voice model id for line {line['id']}. Set "
                "DEFAULT_VOICE_MODEL_ID in tools/fish.config, add a matching "
                f"{voice_key(line.get('voice', 'announcer'))}, or put "
                "voice_model_id directly on that line in commentary.json."
            )
        out_path = os.path.join(OUT_DIR, f"{line['id']}.{fmt}")
        if dry_run:
            print(f"DRY {line['id']}: voice={line.get('voice', 'default')} event={line['event']}")
            done += 1
            continue
        if os.path.exists(out_path) and not force:
            skipped += 1
            continue
        body = json.dumps({
            "text": line["quote"],
            "reference_id": model_id,
            "format": fmt,
        }).encode("utf-8")
        req = urllib.request.Request(url, data=body, method="POST", headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        })
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                audio = resp.read()
            if len(audio) < 512:
                raise RuntimeError(f"suspiciously small response ({len(audio)} bytes)")
            audio, normalized = normalize_audio(audio, fmt)
            with open(out_path, "wb") as f:
                f.write(audio)
            tag = "" if normalized else "  [ffmpeg not found — raw Fish loudness, unnormalized]"
            print(f"  OK  {line['id']}: \"{line['quote']}\"  [{line['event']}]{tag}")
            done += 1
        except Exception as e:
            print(f"FAIL  {line['id']}: {e}", file=sys.stderr)
            failed += 1

    if dry_run:
        print(f"\n{done} lines validated, 0 network calls.")
    else:
        print(f"\n{done} generated, {skipped} already present, {failed} failed.")
    if done and not dry_run:
        print("Rebuild the APK so the new clips ship in assets/voice/.")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
