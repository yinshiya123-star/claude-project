"""Shared PyAV helpers for re-encoding audio."""

from __future__ import annotations

from fractions import Fraction


class AudioTrack:
    """Resamples and encodes audio into an output container.

    Timestamps come from a running sample count instead of the source: audio in
    downloaded videos can repeat or step back in time, and the muxer rejects that
    ("non monotonically increasing dts", reported as "Invalid argument ... 22").
    The source's start offset is kept, so audio that starts late stays in sync.
    """

    def __init__(self, out, codec: str, rate: int, bit_rate: int):
        from av.audio.resampler import AudioResampler

        self.out = out
        self.rate = rate
        self.stream = out.add_stream(codec, rate=rate)
        self.stream.bit_rate = bit_rate
        self.stream.layout = "stereo"
        self.resampler = AudioResampler(format=self.stream.format.name, layout="stereo", rate=rate)
        self.samples: int | None = None

    def add(self, frame) -> None:
        """Encode a decoded audio frame; pass None at the end to flush."""
        for f in self.resampler.resample(frame):
            if self.samples is None:
                start = f.time if f.time and f.time > 0 else 0.0
                self.samples = round(start * self.rate)
            f.pts = self.samples
            f.time_base = Fraction(1, self.rate)
            self.samples += f.samples
            self.out.mux(self.stream.encode(f))
        if frame is None:
            self.out.mux(self.stream.encode(None))


class AudioFeeder:
    """Feeds the audio of a separate file (e.g. a dubbing track) into an
    AudioTrack, a little at a time as the video advances, so the muxer can
    interleave the two."""

    def __init__(self, path: str, track: AudioTrack):
        import av

        self.container = av.open(path)
        self.frames = self.container.decode(audio=0)
        self.track = track
        self.pending = next(self.frames, None)

    def until(self, t: float | None) -> None:
        """Encode audio up to time t (everything that is left for None)."""
        while self.pending is not None and (t is None or self.pending.time is None or self.pending.time <= t):
            self.track.add(self.pending)
            self.pending = next(self.frames, None)

    def close(self) -> None:
        self.until(None)
        self.container.close()


def picture_stream(container):
    """First real video stream; cover art in MP3/M4A files (attached_pic) doesn't count."""
    import av

    for stream in container.streams.video:
        if not stream.disposition & av.stream.Disposition.attached_pic:
            return stream
    return None


def video_fps(path: str) -> float | None:
    """Frame rate of the video, None for audio-only files."""
    import av

    try:
        with av.open(path) as container:
            stream = picture_stream(container)
            rate = stream and (stream.average_rate or stream.guessed_rate)
            return float(rate) if rate else None
    except Exception:
        return None


def log_ffmpeg_errors() -> None:
    """Let PyAV attach FFmpeg's own error message to exceptions, so a failure
    explains itself instead of only saying "Invalid argument"."""
    import av.logging

    av.logging.set_level(av.logging.ERROR)
