#!/usr/bin/env python3
"""
Improved admin app (NewProject): its video / subtitle test screen gets a 5th card that checks a link with the SAME
engine the viewer app uses. Adds the engine subset (re-packaged to com.my.newproject) + CgEngineCheck, and one card
in CgPlayerActivity. Nothing else in the project changes. Usage:
    python3 patches/apply_admin_patches.py --swb <NewProject .swb> --out <patched .swb>
"""
import argparse, os, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "src", "main", "java", "com", "Cigram", "vid")
JAVA = "data/files/java/com.my.newproject/"
SUBSET = ["CigramEngineException", "CigramCancel", "CigramLog", "CigramHttp", "CigramMediaRef", "CigramPlaybackSource",
          "CigramSourceValidator", "CigramSubtitles", "CigramText"]
OWN = ["CgEngineCheck", "CigramStreamLinkLite"]

ANCHOR = "        // preview\n        LinearLayout pc = CgUi.card(this);\n"
CARD = """        // Cigram engine check: same validator / subtitle reader as the viewer app
        LinearLayout ec = CgUi.card(this);
        ec.addView(CgUi.text(this, "5) فحص بمحرك التطبيق", 14, CgCfg.TEXT, true));
        ec.addView(CgUi.muted(this, "نفس فحص تطبيق المشاهد: هل الرابط فيديو حقيقي (لا صفحة ويب)، قائمة HLS ومقطعها الأول، DRM أو صوت فقط، والترجمة: الترميز وعدد الأسطر والعربية."));
        final LinearLayout engineResult = CgUi.vbox(this);
        TextView eb = CgUi.button(this, "افحص الفيديو والترجمة", 1);
        eb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgEngineCheck.run(CgPlayerActivity.this, video, sub, engineResult);
            }
        });
        ec.addView(eb, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        ec.addView(engineResult);
        ec.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(ec);

"""

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--swb", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    zin = zipfile.ZipFile(a.swb)
    names = {i.filename for i in zin.infolist()}
    play = JAVA + "CgPlayerActivity.java"
    if play not in names or any(JAVA + n + ".java" in names for n in SUBSET + OWN):
        print("not the expected NewProject export (or already patched)", file=sys.stderr); return 2
    t = zin.read(play).decode("utf8")
    if t.count(ANCHOR) != 1:
        print("PATCH FAILED, nothing written: anchor found %d times" % t.count(ANCHOR), file=sys.stderr); return 2
    patched = t.replace(ANCHOR, CARD + ANCHOR)
    new = {}
    for n in SUBSET:
        new[JAVA + n + ".java"] = open(os.path.join(SRC, n + ".java"), encoding="utf8").read().replace("package com.Cigram.vid;", "package com.my.newproject;", 1)
    for n in OWN:
        new[JAVA + n + ".java"] = open(os.path.join(ROOT, "admin", n + ".java"), encoding="utf8").read()
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with zipfile.ZipFile(a.out, "w") as zo:
        for i in zin.infolist():
            d = patched.encode("utf8") if i.filename == play else zin.read(i.filename)
            ni = zipfile.ZipInfo(i.filename, i.date_time); ni.compress_type = i.compress_type
            ni.external_attr = i.external_attr; ni.create_system = i.create_system
            zo.writestr(ni, d)
        stamp = zin.infolist()[0].date_time
        for n, c in new.items():
            ni = zipfile.ZipInfo(n, stamp); ni.compress_type = zipfile.ZIP_DEFLATED; ni.create_system = 0
            zo.writestr(ni, c.encode("utf8"))
    print("written:", a.out, "| added", len(new), "files | changed CgPlayerActivity.java")
    return 0

if __name__ == "__main__":
    sys.exit(main())
