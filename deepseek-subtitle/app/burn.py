"""Burn subtitles into a video (hard subtitles) with PyAV + Pillow.

Every frame is decoded, the active subtitle is drawn onto it and the result is
re-encoded as H.264 + AAC MP4. No external ffmpeg is needed: PyAV's wheels ship
libx264. Audio-only uploads become a black video carrying the subtitles.
"""

from __future__ import annotations

import os
import subprocess
from functools import lru_cache
from typing import Callable

from .media import AudioTrack
from .subtitles import Segment, cue_lines

# Common CJK-capable system fonts, tried in order (override with SUBTITLE_FONT).
FONT_CANDIDATES = [
    # Windows
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/msyhbd.ttc",
    "C:/Windows/Fonts/simhei.ttf",
    "C:/Windows/Fonts/simsun.ttc",
    # macOS
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/Hiragino Sans GB.ttc",
    "/System/Library/Fonts/STHeiti Medium.ttc",
    "/Library/Fonts/Arial Unicode.ttf",
    # Linux
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
    "/usr/share/fonts/wenquanyi/wqy-microhei/wqy-microhei.ttc",
]

AUDIO_ONLY_SIZE = (1280, 720)
AUDIO_ONLY_FPS = 10


@lru_cache(maxsize=1)
def find_font() -> str:
    custom = os.getenv("SUBTITLE_FONT")
    if custom:
        if not os.path.exists(custom):
            raise RuntimeError(f"SUBTITLE_FONT 指定的字体不存在：{custom}")
        return custom
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return path
    try:  # Linux / macOS with fontconfig
        out = subprocess.run(["fc-list", ":lang=zh", "file"], capture_output=True, text=True, timeout=10).stdout
        for line in out.splitlines():
            path = line.split(":")[0].strip()
            if path:
                return path
    except Exception:
        pass
    raise RuntimeError("没有找到中文字体。请安装中文字体（如 Noto Sans CJK），或在 .env 中用 SUBTITLE_FONT 指定字体文件路径")


def _wrap(text: str, font, max_width: float) -> list[str]:
    """Wrap to max_width: by word for Latin text, by character for CJK."""
    lines: list[str] = []
    current = ""
    tokens = text.split(" ") if text.isascii() else list(text)
    sep = " " if text.isascii() else ""
    for token in tokens:
        candidate = f"{current}{sep}{token}" if current else token
        if current and font.getlength(candidate) > max_width:
            lines.append(current)
            current = token
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines


class SubtitleRenderer:
    """Renders each segment once into an RGBA strip and blends it onto frames."""

    def __init__(self, width: int, height: int):
        from PIL import ImageFont

        font_path = find_font()
        self.width, self.height = width, height
        base = max(16, round(min(width, height) * 0.05))  # short side: portrait videos too
        self.fonts = [ImageFont.truetype(font_path, base), ImageFont.truetype(font_path, round(base * 0.8))]
        self.margin = round(height * 0.05)
        self.cache: dict[int, tuple] = {}

    def _render(self, seg: Segment, mode: str):
        import numpy as np
        from PIL import Image, ImageDraw

        # First line (Chinese / original) at full size, following lines smaller.
        rows = []
        for i, text in enumerate(cue_lines(seg, mode)):
            font = self.fonts[min(i, 1)]
            rows += [(line, font) for line in _wrap(text, font, self.width * 0.9)]
        if not rows:
            return None
        spacing = round(self.fonts[0].size * 0.25)
        heights = [font.getbbox("国Ag")[3] for _, font in rows]
        strip_h = sum(heights) + spacing * (len(rows) + 1)
        img = Image.new("RGBA", (self.width, strip_h), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        y = spacing
        for (line, font), h in zip(rows, heights):
            x = (self.width - font.getlength(line)) / 2
            draw.text((x, y), line, font=font, fill=(255, 255, 255, 255),
                      stroke_width=max(2, font.size // 12), stroke_fill=(0, 0, 0, 255))
            y += h + spacing
        rgba = np.asarray(img, dtype=np.float32)
        top = max(0, self.height - self.margin - strip_h)
        strip_h = min(strip_h, self.height - top)
        rgba = rgba[:strip_h]
        return top, rgba[..., :3], rgba[..., 3:] / 255.0

    def draw(self, rgb, seg: Segment, mode: str):
        """Blend `seg` onto an HxWx3 uint8 array in place."""
        if seg.id not in self.cache:
            self.cache[seg.id] = self._render(seg, mode)
        rendered = self.cache[seg.id]
        if rendered is None:
            return
        top, color, alpha = rendered
        region = rgb[top : top + color.shape[0]].astype("float32")
        rgb[top : top + color.shape[0]] = (region * (1 - alpha) + color * alpha).astype("uint8")


def _fit(rgb, width: int, height: int):
    """Scale a frame whose size differs from the output (mid-stream resolution change)."""
    import numpy as np
    from PIL import Image

    if rgb.shape[0] - height in (0, 1) and rgb.shape[1] - width in (0, 1):
        return rgb[:height, :width]  # only the odd-size crop
    return np.asarray(Image.fromarray(rgb).resize((width, height)))


class _ActiveSegment:
    """Finds the segment active at time t, for (mostly) increasing t."""

    def __init__(self, segments: list[Segment]):
        self.segments = sorted(segments, key=lambda s: s.start)
        self.i = 0

    def at(self, t: float) -> Segment | None:
        segs = self.segments
        if self.i and segs[self.i - 1].start > t:
            self.i = 0  # time went backwards
        while self.i < len(segs) and segs[self.i].end <= t:
            self.i += 1
        if self.i < len(segs) and segs[self.i].start <= t:
            return segs[self.i]
        return None


def _rotate(arr, rotation):
    """Apply the stream's display rotation (phone videos). FFmpeg reports it in
    degrees counter-clockwise, the same direction np.rot90 turns for k > 0."""
    import numpy as np

    k = round(float(rotation or 0) / 90) % 4
    return np.ascontiguousarray(np.rot90(arr, k)) if k else arr


def _picture_stream(container):
    """First real video stream; cover art in MP3/M4A files (attached_pic) doesn't count."""
    import av

    for stream in container.streams.video:
        if not stream.disposition & av.stream.Disposition.attached_pic:
            return stream
    return None


def _video_size(src: str) -> tuple[int, int] | None:
    """(width, height) of the first frame as displayed, i.e. after rotation."""
    import av

    with av.open(src) as inp:
        stream = _picture_stream(inp)
        if stream is None:
            return None
        for frame in inp.decode(stream):
            k = round(float(getattr(frame, "rotation", 0) or 0) / 90) % 4
            return (frame.height, frame.width) if k % 2 else (frame.width, frame.height)
    return None


def burn(
    src: str,
    dst: str,
    segments: list[Segment],
    mode: str,
    on_progress: Callable[[float], None] | None = None,
) -> None:
    import av
    import numpy as np

    # Output size must be known before anything is muxed: writing the first
    # packet opens every encoder, after which the size can't change.
    size = _video_size(src)
    with av.open(src) as inp:
        vin = _picture_stream(inp)
        ain = inp.streams.audio[0] if inp.streams.audio else None
        if vin is None and ain is None:
            raise RuntimeError("文件里既没有画面也没有声音")
        duration = float(inp.duration / av.time_base) if inp.duration else max((s.end for s in segments), default=0)
        tracker = _ActiveSegment(segments)

        with av.open(dst, "w", format="mp4") as out:
            if vin is not None and size:
                fps = vin.average_rate or vin.guessed_rate or 25
                width, height = size
            else:
                vin = None  # no decodable picture: treat as audio-only
                fps = AUDIO_ONLY_FPS
                width, height = AUDIO_ONLY_SIZE
            vout = out.add_stream("libx264", rate=fps, options={"preset": "veryfast", "crf": "20"})
            vout.pix_fmt = "yuv420p"
            vout.width, vout.height = width - width % 2, height - height % 2  # yuv420p needs even sizes
            renderer = SubtitleRenderer(vout.width, vout.height)
            last_pts = -1

            audio = AudioTrack(out, "aac", 48000, 192000) if ain is not None else None

            def encode_video(rgb, t):
                nonlocal last_pts
                if rgb.shape[0] != vout.height or rgb.shape[1] != vout.width:
                    rgb = _fit(rgb, vout.width, vout.height)
                seg = tracker.at(t)
                if seg is not None:
                    rgb = rgb.copy() if not rgb.flags.writeable else rgb
                    renderer.draw(rgb, seg, mode)
                frame = av.VideoFrame.from_ndarray(np.ascontiguousarray(rgb), format="rgb24")
                pts = max(round(t * fps), last_pts + 1)  # keep timestamps strictly increasing
                frame.pts, last_pts = pts, pts
                for packet in vout.encode(frame):
                    out.mux(packet)
                if on_progress and duration:
                    on_progress(min(1.0, t / duration))

            streams = [s for s in (vin, ain) if s is not None]  # vin is None for audio-only
            for packet in inp.demux(*streams):
                try:
                    frames = packet.decode()
                except av.error.InvalidDataError:
                    continue
                for frame in frames:
                    if packet.stream is vin:
                        if frame.time is None:
                            continue
                        rgb = _rotate(frame.to_ndarray(format="rgb24"), getattr(frame, "rotation", 0))
                        encode_video(rgb, float(frame.time))
                    else:
                        audio.add(frame)

            if vin is None:
                # Audio-only: a black picture for the whole duration.
                black = np.zeros((height, width, 3), dtype=np.uint8)
                for i in range(int(duration * fps) + 1):
                    encode_video(black.copy(), i / fps)

            for packet in vout.encode(None):
                out.mux(packet)
            if audio is not None:
                audio.add(None)
    if on_progress:
        on_progress(1.0)
