#!/usr/bin/env python3
"""
Builds a patched Cigram project (.swb) that contains the player engine.

    python3 patches/apply_patches.py --swb <original filmsandseries .swb> --out <patched .swb>

What it does (everything is an exact-anchor replacement: if an anchor is not found exactly once, nothing
is written and the script stops, so a changed project never gets a half-applied patch):

  1. adds the engine classes to data/files/java/com.Cigram.vid/
  2. registers the two new screens in data/Injection/androidmanifest/app_components.txt
  3. CigramStage1InitProvider.java      - «شاهد الآن» for an item without a link goes to the engine
  4. CigramTogetherPlayerActivity.java  - local subtitle file for this device only (never sent to the room)
  5. data/logic (Sketchware block file, AES-128-CBC like Sketchware writes it):
       PlayerActivity  - external subtitles without restart, sources for episodes without a link,
                         fallback to another source of the same episode, source / subtitle rows in the sheet
       SearchXmlActivity - «ابحث في الفهرس العالمي» button

The original file is never modified. The project's `project`, library, permission and resource files are
copied byte for byte.
"""
import argparse
import json
import os
import re
import sys
import zipfile

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "src", "main", "java", "com", "Cigram", "vid")
JAVA_DIR = "data/files/java/com.Cigram.vid/"
KEY = b"sketchwaresecure"


class PatchError(Exception):
    pass


# ----------------------------------------------------------------------------- helpers

def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise PatchError("anchor for '%s' found %d times (expected exactly 1)" % (label, n))
    return text.replace(old, new)


def aes_decrypt(data):
    d = Cipher(algorithms.AES(KEY), modes.CBC(KEY)).decryptor()
    p = d.update(data) + d.finalize()
    u = padding.PKCS7(128).unpadder()
    return u.update(p) + u.finalize()


def aes_encrypt(data):
    p = padding.PKCS7(128).padder()
    x = p.update(data) + p.finalize()
    e = Cipher(algorithms.AES(KEY), modes.CBC(KEY)).encryptor()
    return e.update(x) + e.finalize()


def gson_str(s):
    """Sketchware writes its files with Gson: these are exactly its escapes."""
    out = ['"']
    for ch in s:
        o = ord(ch)
        if ch == '"': out.append('\\"')
        elif ch == '\\': out.append('\\\\')
        elif ch == '\n': out.append('\\n')
        elif ch == '\r': out.append('\\r')
        elif ch == '\t': out.append('\\t')
        elif ch == '\b': out.append('\\b')
        elif ch == '\f': out.append('\\f')
        elif ch in "<>&='": out.append('\\u%04x' % o)
        elif o < 0x20 or o in (0x2028, 0x2029): out.append('\\u%04x' % o)
        else: out.append(ch)
    out.append('"')
    return ''.join(out)


def gson(v):
    if isinstance(v, dict): return '{' + ','.join(gson_str(k) + ':' + gson(x) for k, x in v.items()) + '}'
    if isinstance(v, list): return '[' + ','.join(gson(x) for x in v) + ']'
    if isinstance(v, str): return gson_str(v)
    if v is True: return 'true'
    if v is False: return 'false'
    if v is None: return 'null'
    return repr(v)


def read_java(name):
    with open(os.path.join(SRC, name), encoding="utf8") as f:
        return f.read()


# ----------------------------------------------------------------------------- 2. manifest

MANIFEST_ADD = """
<activity
    android:name="com.Cigram.vid.CigramCatalogSearchActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout|uiMode|fontScale"
    android:theme="@android:style/Theme.Material.NoActionBar"
    android:windowSoftInputMode="adjustResize|stateHidden" />

<activity
    android:name="com.Cigram.vid.CigramSubtitlePickActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout|uiMode"
    android:theme="@android:style/Theme.Translucent.NoTitleBar" />
"""


def patch_manifest(text):
    if "CigramCatalogSearchActivity" in text:
        raise PatchError("manifest already contains the engine screens")
    return text.rstrip("\n") + "\n" + MANIFEST_ADD


# ----------------------------------------------------------------------------- 3. details -> engine

def patch_stage1(t):
    t = replace_once(t, """    private static void openPlayerForTitleItem(
            Activity activity,
            JSONObject source,
            JSONObject parentSeries,
            boolean episodeTransition
    ) {
        if (activity == null || source == null) return;
""", """    private static void openPlayerForTitleItem(
            Activity activity,
            JSONObject source,
            JSONObject parentSeries,
            boolean episodeTransition
    ) {
        openPlayerForTitleItem(activity, source, parentSeries, episodeTransition, false);
    }

    /**
     * Cigram player engine. {@code resolved} is true when the link was just found and validated by the engine
     * (CigramWatchNow): such a link is not written to video_url.txt and is not sent to Cast, because it can
     * carry per-session headers.
     */
    static void openPlayerForTitleItem(
            Activity activity,
            JSONObject source,
            JSONObject parentSeries,
            boolean episodeTransition,
            boolean resolved
    ) {
        if (activity == null || source == null) return;
""", "openPlayerForTitleItem signature")

    t = replace_once(t, """        if (video.length() == 0) {
            Toast.makeText(activity, "رابط الفيديو غير متوفر", Toast.LENGTH_SHORT).show();
            return;
        }
""", """        if (video.length() == 0) {
            // Cigram player engine: no direct link in the library -> find a validated source for exactly this title / episode
            if (!resolved) {
                try {
                    CigramWatchNow.start(activity, source, parentSeries, episodeTransition);
                    return;
                } catch (Throwable engineError) {
                    android.util.Log.w("CigramDetails", "player engine failed", engineError);
                }
            }
            Toast.makeText(activity, "رابط الفيديو غير متوفر", Toast.LENGTH_SHORT).show();
            return;
        }
""", "no-link branch")

    t = replace_once(t, "        if (CigramCastDetails.tryCast(activity, source, parentSeries)) return;",
                     "        if (!resolved && CigramCastDetails.tryCast(activity, source, parentSeries)) return;", "cast")

    t = replace_once(t, """        try {
            java.io.File dataRoot = activity.getFilesDir().getParentFile();""",
                     """        if (!resolved) try {
            java.io.File dataRoot = activity.getFilesDir().getParentFile();""", "video_url.txt")

    t = replace_once(t, '"season", "episode", "episode_number", "skip_segments"}) {',
                     '"season", "episode", "episode_number", "skip_segments",\n'
                     '                    "cg_origin", "cg_catalog_id", "cg_library_id", "cg_tmdb_id", "cg_year", "cg_episode_id"}) {',
                     "extras list")

    t = replace_once(t, """            if (parentSeries != null && jsonText(source, "series_title").length() == 0
                    && jsonText(parentSeries, "series_title").length() == 0) {
                // the player keys history/progress by the series title, never by the episode title""",
                     """            if (!intent.hasExtra("cg_tmdb_id")) {
                // lets the player look for another source of the same title if this link fails
                String cgTmdb = jsonText(source, "tmdb_id");
                if (cgTmdb.length() == 0 && parentSeries != null) cgTmdb = jsonText(parentSeries, "tmdb_id");
                if (cgTmdb.length() > 0) intent.putExtra("cg_tmdb_id", cgTmdb);
            }
            if (parentSeries != null && "tmdb".equals(jsonText(parentSeries, "cg_origin"))) {
                // a series found in the global catalog is not in the library, so the player cannot complete its
                // episode list from the library: hand it over (the search screen keeps it small)
                String cgEpisodes = jsonText(parentSeries, "series_episodes_json");
                if (cgEpisodes.length() > 0 && cgEpisodes.length() < 200000) intent.putExtra("series_episodes_json", cgEpisodes);
            }
            if (parentSeries != null && jsonText(source, "series_title").length() == 0
                    && jsonText(parentSeries, "series_title").length() == 0) {
                // the player keys history/progress by the series title, never by the episode title""",
                     "tmdb extra")
    return t


# ----------------------------------------------------------------------------- 4. together player

def patch_together(t):
    t = replace_once(t, "    private SubtitleView subtitles;\n",
                     "    private SubtitleView subtitles;\n"
                     "    /** ترجمة من ملف على هذا الجهاز فقط: لا تُرسل ولا تُشارك مع الغرفة ولا تؤثر في حالتها. */\n"
                     "    private final CigramSubtitleOverlay localSubs = new CigramSubtitleOverlay();\n", "field")
    t = replace_once(t, "                player.setVideoSurfaceView(surface);\n",
                     "                player.setVideoSurfaceView(surface);\n"
                     "                localSubs.attach(player, subtitles);\n", "attach")
    t = replace_once(t, "                            if (cg != lastCues) { lastCues = cg; subtitles.setCues(cg.cues); }",
                     "                            if (cg != lastCues) { lastCues = cg; if (localSubs.track() == null) subtitles.setCues(cg.cues); }",
                     "poll cues")
    t = replace_once(t, """        b.addView(choiceRow("حجم الترجمة", new String[]{"صغير", "متوسط", "كبير"}, subSize, new ChoiceCb() {
            @Override public void picked(int i) { subSize = i; savePrefs(); styleSubtitles(); rebuildSmartPanel(); }
        }));
""", """        b.addView(choiceRow("حجم الترجمة", new String[]{"صغير", "متوسط", "كبير"}, subSize, new ChoiceCb() {
            @Override public void picked(int i) { subSize = i; savePrefs(); styleSubtitles(); rebuildSmartPanel(); }
        }));
        b.addView(choiceRow("ترجمة من ملف SRT / VTT", new String[]{"اختيار ملف", "إزالة"}, localSubs.track() != null ? 0 : 1, new ChoiceCb() {
            @Override public void picked(int i) {
                if (i == 0) pickLocalSubtitle();
                else { localSubs.setTrack(null); rebuildSmartPanel(); }
            }
        }));
        TextView localNote = CigramUI.label(this, "الترجمة من ملف تبقى على جهازك فقط ولا تُشارك مع الغرفة.", 11f, CigramUI.MUTED, false);
        localNote.setGravity(Gravity.RIGHT);
        b.addView(localNote, new LinearLayout.LayoutParams(-1, -2));
""", "panel row")
    t = replace_once(t, """    private interface ChoiceCb { void picked(int index); }
""", """    /** Picks a subtitle file on this phone; it is drawn here only and is never sent to the room. */
    private void pickLocalSubtitle() {
        CigramSubtitlePickActivity.pick(this, new CigramSubtitlePickActivity.Callback() {
            @Override public void onPicked(CigramSubtitles.Track track, String displayName, android.net.Uri uri) {
                localSubs.setTrack(track);
                localSubs.setEnabled(true);
                styleSubtitles();
                toast("الترجمة على جهازك فقط: " + displayName);
                rebuildSmartPanel();
            }
            @Override public void onError(String message) { toast(message); }
            @Override public void onCancelled() { }
        });
    }

    private interface ChoiceCb { void picked(int index); }
""", "pick method")
    t = replace_once(t, """        releasePlayer();
        super.onDestroy();""", """        try { localSubs.release(); } catch (Throwable ignored) { }
        releasePlayer();
        super.onDestroy();""", "destroy")
    return t


# ----------------------------------------------------------------------------- 5. logic blocks

BRIDGE_DECL = """final boolean[] subtitlesEnabled = { subtitlePrefs.getBoolean("subtitle_enabled", true) };

// Cigram player engine: external subtitles (no restart), sources for episodes without a link,
// fallback to another source of the SAME episode, choice of source.
final CigramPlayerBridge cgBridge = CigramPlayerBridge.create(
    PlayerActivity.this,
    exoPlayer,
    subtitleView,
    trackSelector,
    new CigramPlayerBridge.Host() {
        @Override public boolean subtitlesEnabled() { return subtitlesEnabled[0]; }
        @Override public void restyleSubtitles() { applySubtitleStyle.run(); }
    }
);
"""

EMPTY_LINK_OLD = """        if (video == null || video.trim().equals("")) {
            android.widget.Toast.makeText(
                PlayerActivity.this,
                "رابط الفيديو غير متوفر",
"""
EMPTY_LINK_NEW = """        if (video == null || video.trim().equals("")) {
            // Cigram player engine: an episode without a direct link is resolved (library, then providers)
            try {
                if (cgBridge.resolveMissing(currentSeason[0], currentEpisode[0], startPosition,
                    new CigramPlayerBridge.Loader() {
                        @Override public void load(String v, String s, long st) { MediaLoader.this.load(v, s, st); }
                    })) {
                    return;
                }
            } catch (Throwable cgResolveError) {}
            android.widget.Toast.makeText(
                PlayerActivity.this,
                "رابط الفيديو غير متوفر",
"""

SUB_OLD_START = """        currentVideo[0] = video.trim();
        currentSubtitle[0] =
            subtitle == null ? "" : subtitle.trim();
"""
SUB_BLOCK_BEGIN = "        if (!currentSubtitle[0].equals(\"\")) {\n"
SUB_BLOCK_END = """        } else {

            if (subtitleView != null) {
                subtitleView.setVisibility(android.view.View.GONE);
            }
        }
"""
SUB_NEW = """        // Cigram player engine: the subtitle (declared link, local file or typed link) is drawn by
        // CigramSubtitleOverlay, so it can change at any time without restarting the video.
        try {
            cgBridge.onLoading(currentVideo[0], currentSubtitle[0], currentSeason[0], currentEpisode[0]);
        } catch (Throwable cgLoadError) {}
        if (subtitleView != null) {
            subtitleView.setVisibility(subtitlesEnabled[0] && cgBridge.hasExternalSubtitle()
                ? android.view.View.VISIBLE : android.view.View.GONE);
        }
"""

ERR_OLD = """                    if (
                        exoPlayer.getPlayerError() != null
                    ) {
                        android.widget.Toast.makeText(
                            PlayerActivity.this,
                            "تعذر تشغيل الفيديو",
"""
ERR_NEW = """                    if (
                        exoPlayer.getPlayerError() != null
                    ) {
                        // Cigram player engine: try another source of the same movie / episode first
                        try {
                            if (cgBridge.onPlayerError(exoPlayer.getPlayerError(), safeStart,
                                new CigramPlayerBridge.Loader() {
                                    @Override public void load(String v, String s, long st) { MediaLoader.this.load(v, s, st); }
                                })) {
                                return;
                            }
                        } catch (Throwable cgFailError) {}
                        android.widget.Toast.makeText(
                            PlayerActivity.this,
                            "تعذر تشغيل الفيديو",
"""

TOGGLE_OLD = """                if(subtitleView!=null)subtitleView.setVisibility(subtitlesEnabled[0]&&!currentSubtitle[0].isEmpty()
                    ?android.view.View.VISIBLE:android.view.View.GONE);
                if(subtitlesEnabled[0])applySubtitleStyle.run();
                return subtitlesEnabled[0];
            }
        });
"""
TOGGLE_NEW = """                if(subtitleView!=null)subtitleView.setVisibility(subtitlesEnabled[0]&&cgBridge.hasExternalSubtitle()
                    ?android.view.View.VISIBLE:android.view.View.GONE);
                cgBridge.onSubtitleToggle(subtitlesEnabled[0]);
                if(subtitlesEnabled[0])applySubtitleStyle.run();
                return subtitlesEnabled[0];
            }
        });
        sheet.item("ترجمة من ملف أو رابط",cgBridge.subtitleSummary(),new CigramPlayerSheet.Source(){
            @Override public CigramPlayerSheet.Options load(){
                return new CigramPlayerSheet.Options(cgBridge.subtitleChoices(),cgBridge.subtitleSelected());
            }
            @Override public String pick(int which){
                return cgBridge.subtitlePick(which);
            }
        });
        sheet.item("حجم الترجمة",cgBridge.sizeSummary(),new CigramPlayerSheet.Source(){
            @Override public CigramPlayerSheet.Options load(){
                return new CigramPlayerSheet.Options(cgBridge.sizeChoices(),cgBridge.sizeSelected());
            }
            @Override public String pick(int which){
                return cgBridge.sizePick(which);
            }
        });
        sheet.item("موضع الترجمة",cgBridge.positionSummary(),new CigramPlayerSheet.Source(){
            @Override public CigramPlayerSheet.Options load(){
                return new CigramPlayerSheet.Options(cgBridge.positionChoices(),cgBridge.positionSelected());
            }
            @Override public String pick(int which){
                return cgBridge.positionPick(which);
            }
        });
        sheet.item("مزامنة الترجمة",cgBridge.offsetSummary(),new CigramPlayerSheet.Source(){
            @Override public CigramPlayerSheet.Options load(){
                return new CigramPlayerSheet.Options(cgBridge.offsetChoices(),cgBridge.offsetSelected());
            }
            @Override public String pick(int which){
                return cgBridge.offsetPick(which);
            }
        });
        if(cgBridge.hasSourceChoices()){
            sheet.item("مصدر الفيديو",cgBridge.sourceSummary(),new CigramPlayerSheet.Source(){
                @Override public CigramPlayerSheet.Options load(){
                    return new CigramPlayerSheet.Options(cgBridge.sourceChoices(),cgBridge.sourceSelected());
                }
                @Override public String pick(int which){
                    return cgBridge.sourcePick(which,exoPlayer.getCurrentPosition(),new CigramPlayerBridge.Loader(){
                        @Override public void load(String v,String s,long st){mediaLoader.load(v,s,st);}
                    });
                }
            });
        }
"""


def patch_player_main(code):
    code = replace_once(code, 'final boolean[] subtitlesEnabled = { subtitlePrefs.getBoolean("subtitle_enabled", true) };\n',
                        BRIDGE_DECL, "bridge declaration")
    code = replace_once(code, EMPTY_LINK_OLD, EMPTY_LINK_NEW, "empty link")
    # the subtitle block: from the declared-subtitle `if` to the matching else, replaced as one piece
    a = code.index(SUB_OLD_START)
    b = code.index(SUB_BLOCK_BEGIN, a)
    e = code.index(SUB_BLOCK_END, b)
    if code.count(SUB_OLD_START) != 1 or code.count(SUB_BLOCK_END) != 1:
        raise PatchError("subtitle block anchors are not unique")
    # everything between the builder creation and the subtitle `if` must be the plain MediaItem builder
    between = code[a + len(SUB_OLD_START):b]
    if "SubtitleConfiguration" in between or "setSubtitleConfigurations" in between:
        raise PatchError("unexpected code before the subtitle block")
    old_block = code[b:e + len(SUB_BLOCK_END)]
    if "builder.setSubtitleConfigurations(subtitleList);" not in old_block:
        raise PatchError("subtitle block is not the one expected")
    code = code[:b] + SUB_NEW + code[e + len(SUB_BLOCK_END):]
    code = replace_once(code, ERR_OLD, ERR_NEW, "player error")
    code = replace_once(code, TOGGLE_OLD, TOGGLE_NEW, "subtitle toggle and sheet rows")
    return code


STOP_OLD_FOCUS = """                    if (hasWindowFocus()) {
                        return;
                    }
"""
STOP_NEW_FOCUS = """                    if (hasWindowFocus()) {
                        return;
                    }

                    // Cigram player engine: the system file picker (external subtitle) is in front,
                    // the viewer has not left: do not release the video.
                    if (CigramSubtitlePickActivity.isPicking()) {
                        return;
                    }
"""
STOP_OLD_FINISH = """                    try {

                        if (!isFinishing()) {
                            finish();
                        }
"""
STOP_NEW_FINISH = """                    try { CigramPlayerBridge.releaseActive(); } catch (Throwable cgReleaseError) {}

                    try {

                        if (!isFinishing()) {
                            finish();
                        }
"""


def patch_player_stop(code):
    code = replace_once(code, STOP_OLD_FOCUS, STOP_NEW_FOCUS, "onStop focus")
    code = replace_once(code, STOP_OLD_FINISH, STOP_NEW_FINISH, "onStop release")
    return code


SEARCH_OLD = "cgGlass.bind(cgFilter,24,true); cgGlass.press(cgFilter);\n"
SEARCH_NEW = SEARCH_OLD + """// Cigram player engine: search the global catalog by name (movie / series), then the usual details page
try {
    final android.widget.TextView cgGlobal=new android.widget.TextView(SearchXmlActivity.this);
    cgGlobal.setText("🔎  لم تجد ما تريد؟ ابحث في الفهرس العالمي");
    cgGlobal.setTextColor(0xFFFFFFFF); cgGlobal.setTextSize(13); cgGlobal.setTypeface(cgGlass.font);
    cgGlobal.setGravity(android.view.Gravity.CENTER);
    cgGlobal.setPadding(cgGlass.px(14),cgGlass.px(10),cgGlass.px(14),cgGlass.px(10));
    cgGlass.bind(cgGlobal,20,true); cgGlass.press(cgGlobal);
    android.widget.LinearLayout.LayoutParams cgGlobalLp=new android.widget.LinearLayout.LayoutParams(-1,-2);
    cgGlobalLp.setMargins(cgGlass.px(12),cgGlass.px(6),cgGlass.px(12),cgGlass.px(2));
    cgRoot.addView(cgGlobal,2,cgGlobalLp);
    cgGlobal.setOnClickListener(new android.view.View.OnClickListener(){@Override public void onClick(android.view.View v){
        android.content.Intent cgGlobalIntent=new android.content.Intent(SearchXmlActivity.this,CigramCatalogSearchActivity.class);
        cgGlobalIntent.putExtra("q",cgInput.getText().toString().trim());
        startActivity(cgGlobalIntent);
    }});
} catch (Throwable cgGlobalError) {}
"""


def patch_search(code):
    return replace_once(code, SEARCH_OLD, SEARCH_NEW, "search entry")


def patch_logic(plain):
    """Patches the three blocks inside the decrypted Sketchware logic file; every other line stays byte-identical."""
    lines = plain.split("\n")
    section = None
    done = {"main": 0, "stop": 0, "search": 0}
    for i, line in enumerate(lines):
        if line.startswith("@"):
            section = line.strip()[1:]
            continue
        if not line.startswith("{"):
            continue
        if section not in ("PlayerActivity.java_onCreate_initializeLogic", "PlayerActivity.java_onStop_onStop",
                           "SearchXmlActivity.java_onCreate_initializeLogic"):
            continue
        obj = json.loads(line)
        if gson(obj) != line:
            raise PatchError("line %d is not in Gson form, refusing to rewrite it" % i)
        if obj.get("opCode") != "addSourceDirectly":
            continue
        code = obj["parameters"][0]
        if section == "PlayerActivity.java_onCreate_initializeLogic" and "class MediaLoader" in code:
            obj["parameters"][0] = patch_player_main(code)
            done["main"] += 1
        elif section == "PlayerActivity.java_onStop_onStop" and "cgStopCheck" in code:
            obj["parameters"][0] = patch_player_stop(code)
            done["stop"] += 1
        elif section == "SearchXmlActivity.java_onCreate_initializeLogic" and SEARCH_OLD in code:
            obj["parameters"][0] = patch_search(code)
            done["search"] += 1
        else:
            continue
        lines[i] = gson(obj)
    if done != {"main": 1, "stop": 1, "search": 1}:
        raise PatchError("logic blocks patched: %s (expected one of each)" % done)
    return "\n".join(lines)


# ----------------------------------------------------------------------------- build

def build(swb_in, swb_out):
    zin = zipfile.ZipFile(swb_in)
    names = [i.filename for i in zin.infolist()]
    for needed in ("data/logic", "data/Injection/androidmanifest/app_components.txt",
                   JAVA_DIR + "CigramStage1InitProvider.java", JAVA_DIR + "CigramTogetherPlayerActivity.java"):
        if needed not in names:
            raise PatchError("not a Cigram project export: %s is missing" % needed)
    new_files = sorted(f for f in os.listdir(SRC) if f.endswith(".java"))
    clash = [f for f in new_files if JAVA_DIR + f in names]
    if clash:
        raise PatchError("the project already has these files: %s" % clash)

    patched = {}
    patched["data/Injection/androidmanifest/app_components.txt"] = patch_manifest(
        zin.read("data/Injection/androidmanifest/app_components.txt").decode("utf8")).encode("utf8")
    patched[JAVA_DIR + "CigramStage1InitProvider.java"] = patch_stage1(
        zin.read(JAVA_DIR + "CigramStage1InitProvider.java").decode("utf8")).encode("utf8")
    patched[JAVA_DIR + "CigramTogetherPlayerActivity.java"] = patch_together(
        zin.read(JAVA_DIR + "CigramTogetherPlayerActivity.java").decode("utf8")).encode("utf8")
    patched["data/logic"] = aes_encrypt(patch_logic(aes_decrypt(zin.read("data/logic")).decode("utf8")).encode("utf8"))

    os.makedirs(os.path.dirname(os.path.abspath(swb_out)), exist_ok=True)
    with zipfile.ZipFile(swb_out, "w") as zout:
        for info in zin.infolist():
            data = patched.get(info.filename)
            if data is None:
                data = zin.read(info.filename)
            ni = zipfile.ZipInfo(info.filename, info.date_time)
            ni.compress_type = info.compress_type
            ni.external_attr = info.external_attr
            ni.create_system = info.create_system
            zout.writestr(ni, data)
        stamp = zin.infolist()[0].date_time
        for f in new_files:
            ni = zipfile.ZipInfo(JAVA_DIR + f, stamp)
            ni.compress_type = zipfile.ZIP_DEFLATED
            ni.create_system = 0
            zout.writestr(ni, read_java(f).encode("utf8"))
    return new_files, sorted(patched)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--swb", required=True, help="original Cigram project export (filmsandseries ... .swb)")
    ap.add_argument("--out", required=True, help="where to write the patched project")
    a = ap.parse_args()
    try:
        added, changed = build(a.swb, a.out)
    except PatchError as e:
        print("PATCH FAILED, nothing written: %s" % e, file=sys.stderr)
        return 2
    print("written:", a.out)
    print("added %d files to %s" % (len(added), JAVA_DIR))
    print("changed:", ", ".join(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
