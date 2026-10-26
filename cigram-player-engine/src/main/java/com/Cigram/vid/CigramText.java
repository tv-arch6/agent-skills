package com.Cigram.vid;

import java.util.Locale;

/** Text helpers shared by the catalog and the subtitle code. */
public final class CigramText {
    private CigramText() { }

    /**
     * Search form of a title: lower case, no Arabic diacritics / tatweel, alef and yeh variants unified,
     * Arabic-Indic digits turned into ASCII digits, punctuation turned into single spaces.
     */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x064B && c <= 0x065F) || c == 0x0670 || c == 0x0640 || c == 0x200F || c == 0x200E || c == 0x061C) continue;
            if (c >= 0x0660 && c <= 0x0669) c = (char) ('0' + (c - 0x0660));
            else if (c >= 0x06F0 && c <= 0x06F9) c = (char) ('0' + (c - 0x06F0));
            else if (c == 0x0623 || c == 0x0625 || c == 0x0622 || c == 0x0671) c = 0x0627;   // أ إ آ ٱ -> ا
            else if (c == 0x0649 || c == 0x06CC) c = 0x064A;                                 // ى ی -> ي
            else if (c == 0x0629) c = 0x0647;                                                // ة -> ه
            else if (c == 0x06A9) c = 0x0643;                                                // ک -> ك
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
                space = false;
            } else if (!space) {
                sb.append(' ');
                space = true;
            }
        }
        int n = sb.length();
        if (n > 0 && sb.charAt(n - 1) == ' ') sb.setLength(n - 1);
        return sb.toString();
    }

    /** First plausible release year (1900-2099) written in a search query, or 0. */
    public static int yearIn(String query) {
        if (query == null) return 0;
        String n = normalize(query);
        String[] parts = n.split(" ");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (p.length() == 4) {
                try {
                    int y = Integer.parseInt(p);
                    if (y >= 1900 && y <= 2099) return y;
                } catch (NumberFormatException ignored) { }
            }
        }
        return 0;
    }

    /** The query without a trailing / leading year that was only there to disambiguate. */
    public static String withoutYear(String query) {
        int y = yearIn(query);
        if (y == 0) return query == null ? "" : query.trim();
        String n = normalize(query);
        String ys = String.valueOf(y);
        String[] parts = n.split(" ");
        StringBuilder sb = new StringBuilder();
        boolean removed = false;
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!removed && parts[i].equals(ys)) { removed = true; parts[i] = ""; }
        }
        for (String p : parts) {
            if (p.length() == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }

    /** The query as typed, minus the release year written in it (spelling is kept: this goes to a search API). */
    public static String stripYear(String query) {
        int y = yearIn(query);
        if (y == 0 || query == null) return query == null ? "" : query.trim();
        String[] parts = query.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        boolean removed = false;
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].replaceAll("[()\\[\\]]", "");
            if (!removed && normalize(p).equals(String.valueOf(y))) { removed = true; parts[i] = ""; }
        }
        for (String p : parts) {
            if (p.length() == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }

    /** True when most letters of the text are Arabic-script or Hebrew (needs right-to-left layout). */
    public static boolean mostlyRtl(String s) {
        if (s == null) return false;
        int rtl = 0, latin = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x0590 && c <= 0x08FF) || (c >= 0xFB1D && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) rtl++;
            else if (Character.isLetter(c)) latin++;
        }
        return rtl > 0 && rtl >= latin;
    }

    public static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
