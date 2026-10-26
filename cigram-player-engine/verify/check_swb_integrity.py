#!/usr/bin/env python3
"""The patched .swb must differ from the original in exactly the intended files: nothing else changed, nothing was lost."""
import os
import sys
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "patches"))
from apply_patches import aes_decrypt  # noqa: E402

orig, patched = zipfile.ZipFile(sys.argv[1]), zipfile.ZipFile(sys.argv[2])
assert patched.testzip() is None, "patched zip is corrupt"
on = {i.filename for i in orig.infolist()}
pn = {i.filename for i in patched.infolist()}
assert on <= pn, "files lost: %s" % sorted(on - pn)[:5]
added = sorted(pn - on)
bad = [n for n in added if not n.startswith("data/files/java/com.Cigram.vid/") or not n.endswith(".java")]
assert not bad, "unexpected new files: %s" % bad
changed = sorted(n for n in on if orig.read(n) != patched.read(n))
expected = ["data/Injection/androidmanifest/app_components.txt", "data/files/java/com.Cigram.vid/CigramStage1InitProvider.java",
            "data/files/java/com.Cigram.vid/CigramTogetherPlayerActivity.java", "data/logic"]
assert changed == sorted(expected), "unexpected changes: %s" % changed

# logic: only the three intended lines changed
a = aes_decrypt(orig.read("data/logic")).decode("utf8").split("\n")
b = aes_decrypt(patched.read("data/logic")).decode("utf8").split("\n")
assert len(a) == len(b), "line count changed"
diff = [i for i in range(len(a)) if a[i] != b[i]]
assert len(diff) == 3, "expected 3 changed logic lines, got %d" % len(diff)
# every other file of the Java folder is byte-identical (also covered by `changed` above) and the new ones are UTF-8 with the right package
for n in added:
    t = patched.read(n).decode("utf8")
    assert t.startswith("package com.Cigram.vid;"), n
print("OK: %d files unchanged, 4 patched (%s), %d added, 3 logic lines changed, zip intact" % (len(on) - 4, ", ".join(os.path.basename(c) for c in changed), len(added)))
