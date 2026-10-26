#!/usr/bin/env python3
"""
Syntax / type check of the Sketchware blocks that the patcher edits.

Sketchware pastes every "add source directly" block into the body of the activity's onCreate. This script does
the same: it wraps the blocks (in their block order) in a stub activity class and runs javac on it, once for the
ORIGINAL project and once for the PATCHED one, then compares the errors.

The project's own classes (R, CigramFonts, ...) and most of ExoPlayer are not available outside Sketchware, so
both runs print many "cannot find symbol" errors. What matters is the difference:

  * a syntax error ("expected", "illegal start", "unclosed ...") anywhere  -> FAIL
  * any error that is in the patched run but not in the original run       -> FAIL (printed)

Usage:  check_blocks.py ORIGINAL.swb PATCHED.swb  CLASSPATH  [WORKDIR]
CLASSPATH must contain android-all.jar, the compiled engine classes and the stubs (see compile-glue.sh).
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "patches"))
from apply_patches import aes_decrypt  # noqa: E402

SECTIONS = [
    ("PlayerActivity", "PlayerActivity.java_onCreate_initializeLogic"),
    ("SearchXmlActivity", "SearchXmlActivity.java_onCreate_initializeLogic"),
]
SYNTAX = re.compile(r"(expected|illegal start|unclosed|reached end of file|not a statement|class, interface|else without if|orphaned|unexpected)")


def blocks_in_order(plain, section):
    objs = {}
    sec = None
    for line in plain.split("\n"):
        if line.startswith("@"):
            sec = line.strip()[1:]
            continue
        if sec == section and line.startswith("{"):
            o = json.loads(line)
            objs[str(o["id"])] = o
    nxt = {str(o["nextBlock"]) for o in objs.values() if o.get("nextBlock", -1) not in (-1, None)}
    starts = [i for i in objs if i not in nxt]
    order, cur, seen = [], starts[0] if starts else None, set()
    while cur is not None and cur in objs and cur not in seen:
        seen.add(cur)
        order.append(objs[cur])
        n = objs[cur].get("nextBlock", -1)
        cur = str(n) if n not in (-1, None) else None
    return [o["parameters"][0] for o in order if o.get("opCode") == "addSourceDirectly"]


def compile_variant(swb, classpath, work, tag):
    plain = aes_decrypt(zipfile.ZipFile(swb).read("data/logic")).decode("utf8")
    out = {}
    for cls, section in SECTIONS:
        body = "\n".join(blocks_in_order(plain, section))
        src = ("package com.Cigram.vid;\npublic class %s extends android.app.Activity {\n"
               "  @Override protected void onCreate(android.os.Bundle savedInstanceState) {\n"
               "    super.onCreate(savedInstanceState);\n%s\n  }\n}\n") % (cls, body)
        d = os.path.join(work, tag, cls)
        os.makedirs(os.path.join(d, "com", "Cigram", "vid"), exist_ok=True)
        path = os.path.join(d, "com", "Cigram", "vid", cls + ".java")
        with open(path, "w", encoding="utf8") as f:
            f.write(src)
        r = subprocess.run(["javac", "-proc:none", "-nowarn", "-Xlint:-options", "-source", "8", "-target", "8", "-encoding", "UTF-8",
                            "-Xmaxerrs", "100000", "-cp", classpath, "-d", os.path.join(d, "out"), path],
                           capture_output=True, text=True)
        out[cls] = parse(r.stderr, src)
    return out


def parse(stderr, src):
    """[(kind, message, source line text)] without line numbers, so two runs can be compared."""
    lines = src.split("\n")
    errs = []
    cur = None
    for ln in stderr.split("\n"):
        m = re.match(r"^.*\.java:(\d+): error: (.*)$", ln)
        if m:
            text = lines[int(m.group(1)) - 1].strip() if int(m.group(1)) - 1 < len(lines) else ""
            cur = [m.group(2).strip(), text, ""]
            errs.append(cur)
        elif cur is not None and ln.strip().startswith(("symbol:", "location:")) and not cur[2]:
            cur[2] = ln.strip()
    return [tuple(e) for e in errs]


JAVA_FILES = ["CigramStage1InitProvider", "CigramTogetherPlayerActivity"]


def compile_java_file(swb, name, classpath, work, tag):
    src = zipfile.ZipFile(swb).read("data/files/java/com.Cigram.vid/%s.java" % name).decode("utf8")
    d = os.path.join(work, tag, name)
    os.makedirs(os.path.join(d, "com", "Cigram", "vid"), exist_ok=True)
    path = os.path.join(d, "com", "Cigram", "vid", name + ".java")
    with open(path, "w", encoding="utf8") as f:
        f.write(src)
    r = subprocess.run(["javac", "-proc:none", "-nowarn", "-Xlint:-options", "-source", "8", "-target", "8", "-encoding", "UTF-8",
                        "-Xmaxerrs", "100000", "-cp", classpath, "-d", os.path.join(d, "out"), path],
                       capture_output=True, text=True)
    return parse(r.stderr, src)


def diff_errors(a, b):
    """
    Errors of the patched run that the original run does not have. Errors are compared by (message, symbol), not
    by source line: a modified line that uses a project class this harness does not have (CigramUI, ...) fails the
    same way in both runs and is not news, while a wrong method / argument in the new code is.
    """
    seen = set((e[0], e[2]) for e in a)
    return [e for e in b if (e[0], e[2]) not in seen]


def main():
    orig, patched, cp = sys.argv[1], sys.argv[2], sys.argv[3]
    work = sys.argv[4] if len(sys.argv) > 4 else tempfile.mkdtemp()
    a = compile_variant(orig, cp, work, "original")
    b = compile_variant(patched, cp, work, "patched")
    failed = False
    for name in JAVA_FILES:
        ea = compile_java_file(orig, name, cp, work, "original")
        eb = compile_java_file(patched, name, cp, work, "patched")
        syn = [e for e in eb if SYNTAX.search(e[0]) and not e[0].startswith("cannot find symbol")]
        new = diff_errors(ea, eb)
        print("%-30s original errors: %4d   patched errors: %4d   syntax errors in patched: %d   NEW errors: %d"
              % (name + ".java", len(ea), len(eb), len(syn), len(new)))
        for e in syn + new:
            failed = True
            print("   !", e[0], "|", e[1][:140], "|", e[2])
    for cls, _ in SECTIONS:
        syn = [e for e in b[cls] if SYNTAX.search(e[0]) and not e[0].startswith("cannot find symbol")]
        new = diff_errors(a[cls], b[cls])
        print("%-18s original errors: %4d   patched errors: %4d   syntax errors in patched: %d   NEW errors: %d"
              % (cls, len(a[cls]), len(b[cls]), len(syn), len(new)))
        for e in syn + new:
            failed = True
            print("   !", e[0], "|", e[1][:140], "|", e[2])
    print("RESULT:", "FAIL" if failed else "OK (the patched blocks parse and type-check wherever the symbols exist; no new errors)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
