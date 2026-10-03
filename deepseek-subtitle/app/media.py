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


def log_ffmpeg_errors() -> None:
    """Let PyAV attach FFmpeg's own error message to exceptions, so a failure
    explains itself instead of only saying "Invalid argument"."""
    import av.logging

    av.logging.set_level(av.logging.ERROR)
