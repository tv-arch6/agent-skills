package com.Cigram.vid;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * External subtitles: SRT and WebVTT parsing, validation, SRT -> WebVTT conversion, time shifting.
 * Pure Java (no Android classes), so it is unit-tested on the JVM.
 *
 * Text handling:
 *  - UTF-8 (with / without BOM) is tried first and strictly; UTF-16 is recognised by its BOM or by the
 *    zero bytes of Latin / Arabic text; a file that is not valid UTF-8 is read as windows-1256 when it
 *    looks Arabic (the usual legacy encoding of Arabic subtitle files) and as windows-1252 otherwise;
 *  - SRT timestamps with '.' and VTT timestamps with ',' are both accepted, hours are optional (VTT);
 *  - &lt;i&gt; &lt;b&gt; &lt;u&gt; become styles; every other tag, WebVTT cue settings, ASS overrides
 *    ({\an8} puts the cue at the top) and HTML entities are handled; nothing is ever executed or fetched;
 *  - right-to-left lines get a right-to-left mark at both ends (see {@link Cue#displayText()}) so
 *    punctuation stays on the correct side of Arabic sentences;
 *  - a file that is binary, too big, or has no valid cue is rejected with a clear message.
 */
public final class CigramSubtitles {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CUES = 20000;
    private static final int MAX_CUE_CHARS = 2000;

    public static final int SPAN_ITALIC = 1;
    public static final int SPAN_BOLD = 2;
    public static final int SPAN_UNDERLINE = 3;

    private static final char RLM = '‏';

    private CigramSubtitles() { }

    // ================================================================== model

    public static final class Cue {
        public final long startMs;
        public final long endMs;
        /** Plain text, lines separated by '\n', no markup. */
        public final String text;
        /** Style ranges as {type, start, end} over {@link #text}. */
        public final List<int[]> spans;
        /** True for cues that were placed at the top ({\an7..9}). */
        public final boolean top;

        Cue(long startMs, long endMs, String text, List<int[]> spans, boolean top) {
            this.startMs = startMs; this.endMs = endMs; this.text = text; this.spans = spans; this.top = top;
        }

        /** The text with a right-to-left mark around every right-to-left line (span offsets are NOT valid on it). */
        public String displayText() {
            StringBuilder sb = new StringBuilder(text.length() + 8);
            for (String line : text.split("\n", -1)) {
                if (sb.length() > 0) sb.append('\n');
                if (CigramText.mostlyRtl(line)) sb.append(RLM).append(line).append(RLM); else sb.append(line);
            }
            return sb.toString();
        }

        Cue shifted(long deltaMs) {
            return new Cue(Math.max(0, startMs + deltaMs), Math.max(0, endMs + deltaMs), text, spans, top);
        }
    }

    public static final class Track {
        public final String format;            // "srt" | "vtt"
        public final String charset;           // name of the charset the file was read with
        public final List<Cue> cues;           // sorted by start time
        public final List<String> warnings;    // skipped cues etc. (Arabic, for the UI)
        public final boolean rtl;
        private final long maxDurationMs;

        Track(String format, String charset, List<Cue> cues, List<String> warnings) {
            this.format = format; this.charset = charset; this.cues = Collections.unmodifiableList(cues);
            this.warnings = Collections.unmodifiableList(warnings);
            long max = 0;
            int rtlCues = 0;
            for (Cue c : cues) {
                max = Math.max(max, c.endMs - c.startMs);
                if (CigramText.mostlyRtl(c.text)) rtlCues++;
            }
            this.maxDurationMs = Math.max(max, 1000);
            this.rtl = rtlCues * 2 > cues.size();
        }

        public long lastEndMs() { return cues.isEmpty() ? 0 : cues.get(cues.size() - 1).endMs; }

        /** The cues showing at {@code posMs} (usually 0 or 1, more when cues overlap). */
        public List<Cue> activeAt(long posMs) {
            ArrayList<Cue> out = new ArrayList<Cue>(2);
            int lo = 0, hi = cues.size() - 1, idx = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (cues.get(mid).startMs <= posMs) { idx = mid; lo = mid + 1; } else hi = mid - 1;
            }
            for (int i = idx; i >= 0; i--) {
                Cue c = cues.get(i);
                if (posMs - c.startMs > maxDurationMs) break;
                if (posMs < c.endMs) out.add(0, c);
            }
            return out;
        }

        /** Same cues, every time moved by {@code deltaMs} (negative = earlier). */
        public Track shifted(long deltaMs) {
            ArrayList<Cue> moved = new ArrayList<Cue>(cues.size());
            for (Cue c : cues) moved.add(c.shifted(deltaMs));
            return new Track(format, charset, moved, new ArrayList<String>(warnings));
        }
    }

    // ================================================================== parse

    private static final Pattern TIME = Pattern.compile(
            "^\\s*(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})(.*)$");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)(?:[\\s.][^>]*)?>|<\\d{1,2}:\\d{2}(?::\\d{2})?[.,]\\d{1,3}>");
    private static final Pattern ASS_AN = Pattern.compile("\\{\\\\an?(\\d)\\}");
    private static final Pattern ASS_ANY = Pattern.compile("\\{[^}]*\\}");

    /** Parses SRT or WebVTT bytes. Throws {@link CigramEngineException#PARSE} with a viewer-facing message. */
    public static Track parse(byte[] data) throws CigramEngineException {
        if (data == null || data.length == 0) throw bad("ملف الترجمة فارغ.");
        if (data.length > MAX_BYTES) throw bad("ملف الترجمة كبير جداً (الحد الأقصى 2 ميغابايت).");
        Decoded d = decode(data);
        String text = d.text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.indexOf('\u0000') >= 0) throw bad("هذا الملف ليس ملف ترجمة نصياً.");

        String head = text.trim();
        boolean vtt = head.startsWith("WEBVTT");
        ArrayList<String> warnings = new ArrayList<String>();
        ArrayList<Cue> cues = new ArrayList<Cue>();
        int skipped = 0;
        for (String block : text.split("\n{2,}")) {
            if (cues.size() >= MAX_CUES) { warnings.add("تم تجاهل ما بعد " + MAX_CUES + " سطر ترجمة."); break; }
            String b = block.trim();
            if (b.length() == 0) continue;
            if (vtt && (b.startsWith("WEBVTT") || b.startsWith("NOTE") || b.startsWith("STYLE") || b.startsWith("REGION"))) {
                // a header block may carry the first cue right after it: "WEBVTT\n\n" normally, but be lenient
                if (!(b.startsWith("WEBVTT") && b.contains("-->"))) continue;
            }
            String[] lines = b.split("\n");
            int ti = -1;
            Matcher m = null;
            for (int i = 0; i < lines.length && i < 3; i++) {
                Matcher mm = TIME.matcher(lines[i]);
                if (mm.matches()) { ti = i; m = mm; break; }
            }
            if (ti < 0) { if (b.contains("-->")) skipped++; continue; }
            long start = ms(m.group(1), m.group(2), m.group(3), m.group(4));
            long end = ms(m.group(5), m.group(6), m.group(7), m.group(8));
            if (end <= start) { skipped++; continue; }
            StringBuilder raw = new StringBuilder();
            for (int i = ti + 1; i < lines.length; i++) {
                if (raw.length() > 0) raw.append('\n');
                raw.append(lines[i]);
            }
            boolean[] top = new boolean[1];
            ArrayList<int[]> spans = new ArrayList<int[]>();
            String clean = cleanText(raw.toString(), spans, top);
            if (clean.length() == 0) continue;
            if (clean.length() > MAX_CUE_CHARS) { clean = clean.substring(0, MAX_CUE_CHARS); spans.clear(); }
            cues.add(new Cue(start, end, clean, spans, top[0]));
        }
        if (cues.isEmpty()) throw bad("لم يُعثر على أي سطر ترجمة صالح في الملف. الصيغ المدعومة: SRT و VTT.");
        Collections.sort(cues, new Comparator<Cue>() {
            @Override public int compare(Cue a, Cue b) { return a.startMs == b.startMs ? 0 : (a.startMs < b.startMs ? -1 : 1); }
        });
        if (skipped > 0) warnings.add("تم تجاهل " + skipped + " سطر ترجمة بتوقيت غير صالح.");
        return new Track(vtt ? "vtt" : "srt", d.charset, cues, warnings);
    }

    private static CigramEngineException bad(String msg) { return new CigramEngineException(CigramEngineException.PARSE, msg); }

    private static long ms(String h, String m, String s, String frac) {
        long hh = h == null ? 0 : Long.parseLong(h);
        long mm = Long.parseLong(m), ss = Long.parseLong(s);
        String f = (frac + "00").substring(0, 3);
        return ((hh * 60 + mm) * 60 + ss) * 1000 + Long.parseLong(f);
    }

    // ------------------------------------------------------------------ text cleanup

    static String cleanText(String raw, List<int[]> spansOut, boolean[] topOut) {
        String s = raw;
        Matcher an = ASS_AN.matcher(s);
        while (an.find()) {
            int pos = an.group(1).charAt(0) - '0';
            if (pos >= 7 && pos <= 9) topOut[0] = true;
        }
        s = ASS_ANY.matcher(s).replaceAll("");

        StringBuilder out = new StringBuilder(s.length());
        int[] open = new int[4];
        java.util.Arrays.fill(open, -1);
        Matcher t = TAG.matcher(s);
        int last = 0;
        while (t.find()) {
            appendDecoded(out, s.substring(last, t.start()));
            last = t.end();
            String name = t.group(2) == null ? "" : t.group(2).toLowerCase(Locale.US);
            boolean close = "/".equals(t.group(1));
            int type = "i".equals(name) || "em".equals(name) ? SPAN_ITALIC : "b".equals(name) || "strong".equals(name) ? SPAN_BOLD : "u".equals(name) ? SPAN_UNDERLINE : 0;
            if (type == 0) continue;
            if (!close) {
                open[type] = out.length();
            } else if (open[type] >= 0) {
                if (out.length() > open[type]) spansOut.add(new int[]{type, open[type], out.length()});
                open[type] = -1;
            }
        }
        appendDecoded(out, s.substring(last));
        for (int type = 1; type <= 3; type++) {
            if (open[type] >= 0 && out.length() > open[type]) spansOut.add(new int[]{type, open[type], out.length()});
        }
        // trim lines and drop empty ones, keeping span offsets right
        StringBuilder trimmed = new StringBuilder(out.length());
        int[] map = new int[out.length() + 1];
        int pos = 0;
        String[] lines = out.toString().split("\n", -1);
        for (String line : lines) {
            int lead = 0;
            while (lead < line.length() && Character.isWhitespace(line.charAt(lead))) lead++;
            int endTrim = line.length();
            while (endTrim > lead && Character.isWhitespace(line.charAt(endTrim - 1))) endTrim--;
            if (endTrim > lead) {
                if (trimmed.length() > 0) trimmed.append('\n');
                for (int i = lead; i <= endTrim && pos + i < map.length; i++) map[pos + i] = trimmed.length() + (i - lead);
                trimmed.append(line, lead, endTrim);
            }
            pos += line.length() + 1;
        }
        ArrayList<int[]> fixed = new ArrayList<int[]>();
        for (int[] sp : spansOut) {
            int a = sp[1] < map.length ? map[sp[1]] : 0, b = sp[2] < map.length ? map[sp[2]] : trimmed.length();
            if (b > trimmed.length()) b = trimmed.length();
            if (b > a) fixed.add(new int[]{sp[0], a, b});
        }
        spansOut.clear();
        spansOut.addAll(fixed);
        return trimmed.toString();
    }

    private static void appendDecoded(StringBuilder out, String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 8) {
                    String ent = s.substring(i + 1, semi);
                    String rep = entity(ent);
                    if (rep != null) { out.append(rep); i = semi + 1; continue; }
                }
            }
            out.append(c);
            i++;
        }
    }

    private static String entity(String e) {
        if ("amp".equals(e)) return "&";
        if ("lt".equals(e)) return "<";
        if ("gt".equals(e)) return ">";
        if ("quot".equals(e)) return "\"";
        if ("apos".equals(e)) return "'";
        if ("nbsp".equals(e)) return " ";
        if ("lrm".equals(e)) return "‎";
        if ("rlm".equals(e)) return "‏";
        if (e.startsWith("#")) {
            try {
                int cp = e.startsWith("#x") || e.startsWith("#X") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                if (cp > 0 && cp < 0x110000 && !(cp >= 0xD800 && cp < 0xE000)) return new String(Character.toChars(cp));
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    // ------------------------------------------------------------------ charset

    static final class Decoded {
        final String text;
        final String charset;
        Decoded(String text, String charset) { this.text = text; this.charset = charset; }
    }

    static Decoded decode(byte[] b) throws CigramEngineException {
        int n = b.length;
        if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new Decoded(new String(b, 3, n - 3, Charset.forName("UTF-8")), "UTF-8");
        }
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return new Decoded(new String(b, 2, n - 2, Charset.forName("UTF-16LE")), "UTF-16LE");
        if (n >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return new Decoded(new String(b, 2, n - 2, Charset.forName("UTF-16BE")), "UTF-16BE");

        int zerosEven = 0, zerosOdd = 0, sample = Math.min(n, 4096);
        for (int i = 0; i < sample; i++) if (b[i] == 0) { if ((i & 1) == 0) zerosEven++; else zerosOdd++; }
        if (sample >= 16 && zerosOdd * 4 > sample && zerosEven * 20 < sample) return new Decoded(new String(b, Charset.forName("UTF-16LE")), "UTF-16LE");
        if (sample >= 16 && zerosEven * 4 > sample && zerosOdd * 20 < sample) return new Decoded(new String(b, Charset.forName("UTF-16BE")), "UTF-16BE");
        if (zerosEven + zerosOdd > 0) throw bad("هذا الملف ليس ملف ترجمة نصياً.");

        try {
            CharsetDecoder dec = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer cb = dec.decode(ByteBuffer.wrap(b));
            return new Decoded(cb.toString(), "UTF-8");
        } catch (CharacterCodingException notUtf8) {
            int high = 0;
            for (byte x : b) if ((x & 0x80) != 0) high++;
            String arabic = new String(b, Charset.forName("windows-1256"));
            int arabicLetters = 0;
            for (int i = 0; i < arabic.length(); i++) {
                char c = arabic.charAt(i);
                if (c >= 0x0621 && c <= 0x064A) arabicLetters++;
            }
            if (high > 0 && arabicLetters * 100 / high >= 60) return new Decoded(arabic, "windows-1256");
            return new Decoded(new String(b, Charset.forName("windows-1252")), "windows-1252");
        }
    }

    // ================================================================== output

    /** A valid WebVTT document for the track (UTF-8 when written with {@link #utf8(String)}). */
    public static String toWebVtt(Track t) {
        StringBuilder sb = new StringBuilder(t.cues.size() * 64 + 16);
        sb.append("WEBVTT\n\n");
        int n = 1;
        for (Cue c : t.cues) {
            sb.append(n++).append('\n');
            sb.append(vttTime(c.startMs)).append(" --> ").append(vttTime(c.endMs));
            if (c.top) sb.append(" line:10%");
            sb.append('\n');
            sb.append(escapeVtt(c)).append("\n\n");
        }
        return sb.toString();
    }

    /** hh:mm:ss.mmm, for diagnostics screens. */
    public static String vttTimeOf(long ms) { return vttTime(ms); }

    static String vttTime(long ms) {
        long h = ms / 3600000L, m = (ms / 60000L) % 60, s = (ms / 1000L) % 60, f = ms % 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, f);
    }

    private static String escapeVtt(Cue c) {
        String t = c.text;
        StringBuilder sb = new StringBuilder(t.length() + 16);
        for (int i = 0; i < t.length(); i++) {
            for (int[] sp : c.spans) if (sp[1] == i) sb.append(openTag(sp[0]));
            char ch = t.charAt(i);
            if (ch == '&') sb.append("&amp;");
            else if (ch == '<') sb.append("&lt;");
            else if (ch == '>') sb.append("&gt;");
            else sb.append(ch);
            for (int[] sp : c.spans) if (sp[2] == i + 1) sb.append(closeTag(sp[0]));
        }
        String out = sb.toString();
        // an empty line would end the cue; "-->" inside the text would start a new timing line
        return out.replace("\n\n", "\n").replace("-->", "-&gt;");
    }

    private static String openTag(int type) { return type == SPAN_ITALIC ? "<i>" : type == SPAN_BOLD ? "<b>" : "<u>"; }

    private static String closeTag(int type) { return type == SPAN_ITALIC ? "</i>" : type == SPAN_BOLD ? "</b>" : "</u>"; }

    public static byte[] utf8(String s) { return s.getBytes(Charset.forName("UTF-8")); }

    // ================================================================== remote subtitle fetch

    /** Where a subtitle link comes from: only these two are ever downloaded. */
    public static final int TRUST_PROVIDER = 1;   // declared by the library or an integrated provider
    public static final int TRUST_MANUAL = 2;     // typed / pasted by the viewer on purpose

    /**
     * Downloads and validates a subtitle file. Refuses anything that is not https (plain http only for
     * loopback and for links the library / a provider declared), anything over 2 MB, and anything that is a web page, an image, audio or video.
     * Never called for a link the viewer did not give and no provider declared.
     */
    public static Track fetch(String url, java.util.Map<String, String> headers, int trust, CigramCancel cancel) throws CigramEngineException {
        if (trust != TRUST_PROVIDER && trust != TRUST_MANUAL) {
            throw new CigramEngineException(CigramEngineException.UNSUPPORTED, "لا تُحمَّل ترجمات من مصادر غير موثوقة تلقائياً.");
        }
        String u = url == null ? "" : url.trim();
        boolean https = u.startsWith("https://");
        boolean loopback = u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost");
        // The library's own subtitle links may be plain http (they were before; they are the owner's files). A link the viewer typed must be https.
        boolean ownerHttp = trust == TRUST_PROVIDER && u.startsWith("http://");
        if (!https && !loopback && !ownerHttp) throw new CigramEngineException(CigramEngineException.UNSUPPORTED, "رابط الترجمة يجب أن يبدأ بـ https.");
        CigramHttp.Request req = new CigramHttp.Request(u).headers(headers).max(MAX_BYTES + 1).timeouts(8000, 12000);
        CigramHttp.Response res = CigramHttp.execute(req, cancel);
        if (!res.ok()) throw CigramEngineException.http(res.code);
        if (res.truncated || res.body.length > MAX_BYTES) throw bad("ملف الترجمة كبير جداً (الحد الأقصى 2 ميغابايت).");
        String ct = res.contentType.toLowerCase(Locale.US);
        if (ct.startsWith("text/html") || ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/")) {
            throw bad("الرابط لا يشير إلى ملف ترجمة.");
        }
        return parse(res.body);
    }

    /** Subtitle language / kind guessed from a link or file name: "srt", "vtt" or "". */
    public static String formatHint(String nameOrUrl) {
        String l = nameOrUrl == null ? "" : nameOrUrl.toLowerCase(Locale.US);
        int q = l.indexOf('?');
        if (q >= 0) l = l.substring(0, q);
        if (l.endsWith(".srt")) return "srt";
        if (l.endsWith(".vtt")) return "vtt";
        return "";
    }
}
