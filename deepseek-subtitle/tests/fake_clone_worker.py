"""Stand-in for app.clone_worker in tests: answers like the real worker, crashes like a
native library would (no Python traceback, odd exit code) on text containing "崩溃"."""

import json
import os
import sys
import wave

print(json.dumps({"ready": True}), flush=True)
for line in sys.stdin:
    req = json.loads(line)
    if "崩溃" in req["text"]:
        sys.stderr.write("native crash in ZipVoice\n")
        sys.stderr.flush()
        os._exit(3)
    with wave.open(req["out"], "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(24000)
        w.writeframes(b"\x10\x00" * int(len(req["text"]) * 0.2 * 24000))
    print(json.dumps({"ok": True}), flush=True)
