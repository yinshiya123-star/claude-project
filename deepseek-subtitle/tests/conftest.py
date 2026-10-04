import pytest


@pytest.fixture(autouse=True)
def no_voiceprint_download(monkeypatch):
    """Tests never download the voiceprint model; speaker grouping falls back to pitch
    unless a test provides its own voiceprints."""
    from app import speakers

    monkeypatch.setattr(speakers, "available", lambda: False)
