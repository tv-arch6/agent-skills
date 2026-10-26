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
OWN = ["CgEngineCheck", "CigramStreamLinkLite", "NxProviderLinks"]
WORKER = "data/files/admin-worker-v2/cigram-admin-worker-v2.js"

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

MOVIE_EDITS = [
    ("field", '    private String subtitle = "";\n',
     '    private String subtitle = "";\n    private String providerId = "";     // set when the movie is attached to a provider\'s own player page\n'),
    ("tabs", 'mediaBox.addView(NxUi.segmented(this, new String[]{"من الجهاز", "برابط"}, new int[]{NxIcons.PHONE, CgIcons.LINK}, mode, new NxUi.SegCb() {',
     'mediaBox.addView(NxUi.segmented(this, new String[]{"من الجهاز", "برابط", "من المزوّد"}, new int[]{NxIcons.PHONE, CgIcons.LINK, CgIcons.PLAY}, mode, new NxUi.SegCb() {'),
    ("provider box", """        } else {
            mediaBox.addView(linkBox(), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        }
""", """        } else if (mode == 2) {
            mediaBox.addView(NxProviderLinks.box(this, st, new Runnable() {
                @Override
                public void run() {
                    renderMedia();
                }
            }, new CgUi.Callback<String[]>() {
                @Override
                public void run(String[] pick) {
                    providerId = pick[1];
                    attach(pick[0], "provider");
                }
            }), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        } else {
            mediaBox.addView(linkBox(), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        }
"""),
    ("attach", """        videoCheck = "upload".equals(source) ? "ok" : "checking";
        render();
        if (!"upload".equals(source)) checkLink(video);""",
     """        if (!"provider".equals(source)) providerId = "";
        // a provider page is not a video file: the file check does not apply to it
        videoCheck = "upload".equals(source) || "provider".equals(source) ? "ok" : "checking";
        render();
        if ("link".equals(source)) checkLink(video);"""),
    ("fields", """        CxJ.put(f, "link", video);
""", """        CxJ.put(f, "link", video);
        if ("provider".equals(videoSource)) {
            CxJ.put(f, "video", "");
            CxJ.put(f, "link", "");
            CxJ.put(f, "embed_url", video);
            CxJ.put(f, "embed_provider", providerId);
        }
"""),
    ("attached label", '"upload".equals(videoSource) ? "تم رفع الفيلم وربطه بالبطاقة" : "تم ربط الرابط بالبطاقة"',
     '"upload".equals(videoSource) ? "تم رفع الفيلم وربطه بالبطاقة" : "provider".equals(videoSource) ? "تم ربط مشغّل المزوّد بالبطاقة" : "تم ربط الرابط بالبطاقة"'),
]
PREVIEW_OLD = 'CxMedia.openPlayer(NxMovieActivity.this, CxJ.s(st, "title"), video, subtitle);'
PREVIEW_NEW = ('if ("provider".equals(videoSource)) { try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(video))); } '
               'catch (Throwable ignored) { } } else CxMedia.openPlayer(NxMovieActivity.this, CxJ.s(st, "title"), video, subtitle);')


def patch_movie(t):
    for label, old, new in MOVIE_EDITS:
        if t.count(old) != 1:
            raise SystemExit("PATCH FAILED, nothing written: NxMovieActivity anchor '%s' found %d times" % (label, t.count(old)))
        t = t.replace(old, new)
    if t.count(PREVIEW_OLD) < 1:
        raise SystemExit("PATCH FAILED: preview anchor missing")
    return t.replace(PREVIEW_OLD, PREVIEW_NEW)


def patch_worker(t):
    old_s = '"imdb_id", "original_title",\n]);'
    old_u = 'const URL_FIELDS = new Set(["poster", "series_poster", "season_poster", "backdrop", "video", "link", "subtitle"]);'
    if t.count(old_s) != 1 or t.count(old_u) != 1:
        raise SystemExit("PATCH FAILED, nothing written: worker field lists not found")
    t = t.replace(old_s, '"imdb_id", "original_title",\n  // provider player page (the item has no video file; the viewer app opens that provider\'s own player)\n  "embed_url", "embed_provider",\n]);')
    return t.replace(old_u, 'const URL_FIELDS = new Set(["poster", "series_poster", "season_poster", "backdrop", "video", "link", "subtitle", "embed_url"]);')


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
    movie = JAVA + "NxMovieActivity.java"
    other = {movie: patch_movie(zin.read(movie).decode("utf8")), WORKER: patch_worker(zin.read(WORKER).decode("utf8"))}
    new = {}
    for n in SUBSET:
        new[JAVA + n + ".java"] = open(os.path.join(SRC, n + ".java"), encoding="utf8").read().replace("package com.Cigram.vid;", "package com.my.newproject;", 1)
    for n in OWN:
        new[JAVA + n + ".java"] = open(os.path.join(ROOT, "admin", n + ".java"), encoding="utf8").read()
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with zipfile.ZipFile(a.out, "w") as zo:
        for i in zin.infolist():
            d = patched.encode("utf8") if i.filename == play else other[i.filename].encode("utf8") if i.filename in other else zin.read(i.filename)
            ni = zipfile.ZipInfo(i.filename, i.date_time); ni.compress_type = i.compress_type
            ni.external_attr = i.external_attr; ni.create_system = i.create_system
            zo.writestr(ni, d)
        stamp = zin.infolist()[0].date_time
        for n, c in new.items():
            ni = zipfile.ZipInfo(n, stamp); ni.compress_type = zipfile.ZIP_DEFLATED; ni.create_system = 0
            zo.writestr(ni, c.encode("utf8"))
    print("written:", a.out, "| added", len(new), "files | changed CgPlayerActivity.java, NxMovieActivity.java, cigram-admin-worker-v2.js")
    return 0

if __name__ == "__main__":
    sys.exit(main())
