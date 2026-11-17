"""Generated local media for real audio/subtitle selection tests (run on workers)."""
import hashlib
import json
from pathlib import Path
import subprocess


VERSION = 1
SUBTITLE = "FLEET SUBTITLE FIXTURE"


def ensure_multitrack(root: Path) -> Path:
    """Remux the existing test pattern with two named AAC tracks and an SRT cue.

    No production URLs or footage are used. Caller must first generate root/index.m3u8.
    The cached, atomically published Matroska file is about twenty seconds long.
    """
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    target = root / "multitrack.mkv"
    manifest = root / "multitrack-manifest.json"
    try:
        saved = json.loads(manifest.read_text())
        if saved.get("version") == VERSION and target.is_file() and target.stat().st_size == saved.get("bytes"):
            return target
    except (OSError, ValueError):
        pass
    source = root / "index.m3u8"
    if not source.is_file():
        raise FileNotFoundError("Generate the local HLS test pattern before creating multitrack.mkv")
    subtitles = root / "multitrack.srt"
    subtitles.write_text("1\n00:00:00,000 --> 00:00:19,500\n" + SUBTITLE + "\n", encoding="utf-8")
    stage = root / "multitrack.building.mkv"
    try:
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-protocol_whitelist", "file,crypto,data", "-i", str(source),
            "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000",
            "-i", str(subtitles), "-map", "0:v:0", "-map", "0:a:0", "-map", "1:a:0", "-map", "2:s:0",
            "-t", "20", "-c:v", "copy", "-c:a", "aac", "-b:a", "96k", "-c:s", "srt",
            "-metadata:s:a:0", "language=eng", "-metadata:s:a:0", "title=English",
            "-metadata:s:a:1", "language=fra", "-metadata:s:a:1", "title=French",
            "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=English subtitles",
            "-disposition:a:0", "default", "-disposition:a:1", "0", "-disposition:s:0", "0",
            "-f", "matroska", str(stage),
        ], check=True, timeout=120)
        probe = json.loads(subprocess.check_output([
            "ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(stage)
        ], timeout=30))
        audio = [s for s in probe["streams"] if s["codec_type"] == "audio"]
        caption = [s for s in probe["streams"] if s["codec_type"] == "subtitle"]
        duration = float(probe["format"]["duration"])
        if len(audio) != 2 or [s.get("tags", {}).get("language") for s in audio] != ["eng", "fra"]:
            raise RuntimeError("Generated fixture did not preserve both audio languages")
        if not all(s["codec_name"] == "aac" for s in audio) or len(caption) != 1 or caption[0]["codec_name"] != "subrip":
            raise RuntimeError("Generated fixture has unexpected audio or subtitle codecs")
        if not (19 <= duration <= 21) or audio[0].get("disposition", {}).get("default") != 1:
            raise RuntimeError("Generated fixture duration/default audio is incorrect")
        stage.replace(target)
        record = {"version": VERSION, "durationSeconds": duration, "bytes": target.stat().st_size,
            "sha256": hashlib.file_digest(target.open("rb"), "sha256").hexdigest(),
            "audio": [{"language": s["tags"]["language"], "title": s["tags"].get("title"), "default": s["disposition"]["default"]} for s in audio],
            "subtitle": SUBTITLE}
        manifest.write_text(json.dumps(record, indent=2) + "\n")
        return target
    finally:
        stage.unlink(missing_ok=True)


if __name__ == "__main__":
    import sys
    print(ensure_multitrack(Path(sys.argv[1])))
