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
          "CigramSourceValidator", "CigramSubtitles", "CigramText", "CigramSourceProvider", "CigramBaseProvider",
          "CigramJsonProvider", "CigramEngineConfig", "CigramMediaRequest", "CigramEpisode"]
OWN = ["CgEngineCheck", "CigramStreamLinkLite", "NxProviderFetch"]
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
     '    private String subtitle = "";\n    private String backups = "";        // provider links kept as backups, one per line\n'),
    ("tabs", 'mediaBox.addView(NxUi.segmented(this, new String[]{"من الجهاز", "برابط"}, new int[]{NxIcons.PHONE, CgIcons.LINK}, mode, new NxUi.SegCb() {',
     'mediaBox.addView(NxUi.segmented(this, new String[]{"من الجهاز", "برابط", "من المزوّد"}, new int[]{NxIcons.PHONE, CgIcons.LINK, CgIcons.PLAY}, mode, new NxUi.SegCb() {'),
    ("provider box", """        } else {
            mediaBox.addView(linkBox(), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        }
""", """        } else if (mode == 2) {
            mediaBox.addView(NxProviderFetch.movieBox(this, st, new Runnable() {
                @Override
                public void run() {
                    renderMedia();
                }
            }, new CgUi.Callback<String[]>() {
                @Override
                public void run(String[] links) {
                    attach(links[0], "provider");
                    backups = links[1];
                    renderMedia();
                }
            }), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        } else {
            mediaBox.addView(linkBox(), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        }
"""),
    ("attach", """        videoCheck = "upload".equals(source) ? "ok" : "checking";
        render();
        if (!"upload".equals(source)) checkLink(video);""",
     """        backups = "";
        // provider links were already checked one by one with the viewer app's own validator
        videoCheck = "upload".equals(source) || "provider".equals(source) ? "ok" : "checking";
        render();
        if ("link".equals(source)) checkLink(video);"""),
    ("fields", """        CxJ.put(f, "link", video);
""", """        CxJ.put(f, "link", video);
        if (backups.length() > 0) CxJ.put(f, "video_backups", backups);
"""),
    ("attached label", '"upload".equals(videoSource) ? "تم رفع الفيلم وربطه بالبطاقة" : "تم ربط الرابط بالبطاقة"',
     '"upload".equals(videoSource) ? "تم رفع الفيلم وربطه بالبطاقة" : "provider".equals(videoSource) ? "تم ربط رابط المزوّد بالبطاقة" + (backups.length() > 0 ? " (+ احتياطي)" : "") : "تم ربط الرابط بالبطاقة"'),
]

SEASON_EDITS = [
    ("season button", "        page.addView(phone, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 6));\n",
     """        page.addView(phone, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 6));
        TextView fromProviders = CgUi.button(this, "⬇  جلب روابط الموسم من المزوّدين (بدون رفع)", 1);
        fromProviders.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collect2();
                int sn = CxJ.n(st, "season");
                if (sn <= 0) {
                    season.error("رقم الموسم مطلوب (1 أو أكثر).");
                    return;
                }
                NxProviderFetch.season(CxSeasonActivity.this, CxJ.s(st, "series_key"), sn, new Runnable() {
                    @Override
                    public void run() {
                        CgData.load(CxSeasonActivity.this, new CgHttp.Done<CgData>() {
                            @Override
                            public void ok(CgData d) { }

                            @Override
                            public void fail(String message) { }
                        });
                    }
                });
            }
        });
        page.addView(fromProviders, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
"""),
]


def apply_edits(t, edits, label):
    for name, old, new in edits:
        if t.count(old) != 1:
            raise SystemExit("PATCH FAILED, nothing written: %s anchor '%s' found %d times" % (label, name, t.count(old)))
        t = t.replace(old, new)
    return t


def patch_movie(t):
    return apply_edits(t, MOVIE_EDITS, "NxMovieActivity")


def patch_season(t):
    return apply_edits(t, SEASON_EDITS, "CxSeasonActivity")


WORKER_EDITS = [
    ("fields", '"imdb_id", "original_title",\n]);',
     '"imdb_id", "original_title",\n  // provider player page (unused by the provider tab now, kept for items that have it)\n  "embed_url", "embed_provider",\n'
     '  // more direct video links of the same movie / episode, one per line: the viewer app falls back to them\n  "video_backups",\n]);'),
    ("url fields", 'const URL_FIELDS = new Set(["poster", "series_poster", "season_poster", "backdrop", "video", "link", "subtitle"]);',
     'const URL_FIELDS = new Set(["poster", "series_poster", "season_poster", "backdrop", "video", "link", "subtitle", "embed_url"]);'),
    ("route", '    "POST /admin/season/commit": A(handleSeasonCommit),\n',
     '    "POST /admin/season/commit": A(handleSeasonCommit),\n    "POST /admin/tmdb/season": A(handleTmdbSeasonEpisodes),\n'),
    ("season episodes handler", "async function handleSeasonPreview(request, env) {\n",
     r"""// Episodes of one season from TMDB (numbers, titles, stills): the admin «من المزوّد» season flow resolves each one.
async function handleTmdbSeasonEpisodes(request, env) {
  const input = await readRequestData(request);
  const season = numOf(input.season);
  if (!season) throw new AdminError("missing_numbers", "رقم الموسم مطلوب.", 400);
  const header = findSeriesHeader(await readMoviesList(env), input.series_id);
  if (!header) throw new AdminError("series_not_found", "المسلسل غير موجود.", 404);
  const tvId = await resolveTmdbTvId(env, header);
  if (!tvId) throw new AdminError("no_tmdb", "لا يوجد معرّف TMDB لهذا المسلسل.", 400);
  const info = await tmdbSeasonInfo(env, tvId, season);
  if (!info.ok) throw new AdminError("tmdb_error", "تعذر جلب حلقات الموسم من TMDB.", 502);
  const episodes = Array.from(info.episodes.entries()).sort((a, b) => a[0] - b[0]).map(([n, m]) => Object.assign({ ep: n }, m));
  return json({ ok: true, tmdb_id: String(tvId), imdb_id: cleanString(header.imdb_id), title: cleanString(header.title), season, episodes });
}

/** "url\nurl" -> the same text when every line is an http(s) link, otherwise an error. */
function cleanBackups(value, where) {
  const lines = cleanString(value).split(/\r?\n/).map((s) => s.trim()).filter(Boolean).slice(0, 8);
  for (const u of lines) {
    if (!/^https?:\/\//i.test(u)) throw new AdminError("invalid_url", "رابط احتياطي غير صالح" + where + ".", 400);
  }
  return lines.join("\n");
}

async function handleSeasonPreview(request, env) {
"""),
    ("commit backups read", "        const subtitle = cleanString(row && row.subtitle);\n",
     "        const subtitle = cleanString(row && row.subtitle);\n        const backups = cleanBackups(row && row.video_backups, \" في الحلقة \" + numOf(row && row.ep));\n"),
    ("commit backups update", """          if (video) {
            existing.video = video;
            existing.link = video;
          }
""", """          if (video) {
            existing.video = video;
            existing.link = video;
            if (backups) existing.video_backups = backups;
          }
"""),
    ("commit backups new", "        if (subtitle) item.subtitle = subtitle;\n        for (const [k, v] of Object.entries(inherit)) {",
     "        if (subtitle) item.subtitle = subtitle;\n        if (backups) item.video_backups = backups;\n        for (const [k, v] of Object.entries(inherit)) {"),
]


def patch_worker(t):
    return apply_edits(t, WORKER_EDITS, "worker")


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
    seasonf = JAVA + "CxSeasonActivity.java"
    other = {movie: patch_movie(zin.read(movie).decode("utf8")), seasonf: patch_season(zin.read(seasonf).decode("utf8")),
             WORKER: patch_worker(zin.read(WORKER).decode("utf8"))}
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
    print("written:", a.out, "| added", len(new), "files | changed CgPlayerActivity.java, NxMovieActivity.java, CxSeasonActivity.java, cigram-admin-worker-v2.js")
    return 0

if __name__ == "__main__":
    sys.exit(main())
