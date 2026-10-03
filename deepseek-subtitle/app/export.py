"""Export an MP3 with the subtitles embedded as ID3 lyrics."""

from __future__ import annotations

import shutil
from pathlib import Path

from .subtitles import Segment, cue_lines, to_lrc

ID3_LANG = {"bilingual": "zho", "zh": "zho", "en": "eng", "orig": "zho"}


def convert_to_mp3(src: str, dst: str) -> None:
    """Re-encode the audio track of any media file to 192 kbps stereo MP3."""
    import av
    from av.audio.resampler import AudioResampler

    with av.open(src) as inp:
        if not inp.streams.audio:
            raise RuntimeError("文件里没有音轨，无法导出 MP3")
        with av.open(dst, "w", format="mp3") as out:
            stream = out.add_stream("libmp3lame", rate=44100)
            stream.bit_rate = 192000
            stream.layout = "stereo"
            resampler = AudioResampler(format=stream.format.name, layout="stereo", rate=44100)
            for frame in inp.decode(audio=0):
                for f in resampler.resample(frame):
                    out.mux(stream.encode(f))
            for f in resampler.resample(None):
                out.mux(stream.encode(f))
            out.mux(stream.encode(None))


def write_lyrics(mp3_path: str, segments: list[Segment], mode: str, title: str) -> None:
    """Embed lyrics into the MP3's ID3 tag.

    USLT holds LRC text (timestamps included): the field most phone and desktop
    players read, and many of them scroll it in sync. SYLT carries the same
    timings in the standard synchronised-lyrics frame for players that use it.
    """
    from mutagen.id3 import ID3, SYLT, TIT2, USLT, ID3NoHeaderError

    try:
        tags = ID3(mp3_path)
    except ID3NoHeaderError:
        tags = ID3()
    lang = ID3_LANG[mode]
    tags.delall("USLT")
    tags.delall("SYLT")
    tags.add(USLT(encoding=1, lang=lang, desc="", text=to_lrc(segments, mode)))
    tags.add(SYLT(
        encoding=1, lang=lang, format=2, type=1, desc="",
        text=[("\n".join(cue_lines(s, mode)), int(s.start * 1000)) for s in segments],
    ))
    if "TIT2" not in tags:
        tags.add(TIT2(encoding=1, text=title))
    # ID3v2.3 + UTF-16 is what Windows Explorer and older players read reliably.
    tags.save(mp3_path, v2_version=3)


def export_mp3(media_path: str, cache_dir: Path, job_id: str, segments: list[Segment], mode: str, title: str) -> Path:
    """Build `<cache_dir>/<job_id>.<mode>.mp3` and return its path."""
    cache_dir.mkdir(parents=True, exist_ok=True)
    if Path(media_path).suffix.lower() == ".mp3":
        base = Path(media_path)
    else:
        # Converting video audio is slow; do it once per job and reuse it.
        base = cache_dir / f"{job_id}.mp3"
        if not base.exists():
            tmp = base.with_suffix(".part.mp3")
            convert_to_mp3(media_path, str(tmp))
            tmp.replace(base)
    out = cache_dir / f"{job_id}.{mode}.mp3"
    shutil.copyfile(base, out)
    write_lyrics(str(out), segments, mode, title)
    return out
