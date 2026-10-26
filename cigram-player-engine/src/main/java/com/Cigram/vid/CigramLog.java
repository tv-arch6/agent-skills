package com.Cigram.vid;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diagnostic log for the player engine. Everything that could carry a secret (query values of
 * token-like parameters, user info in the URL, Authorization / Cookie values) is removed before it
 * reaches logcat. Falls back to stderr where android.util.Log is not available (unit tests).
 */
public final class CigramLog {
    private static final Pattern SECRET_PARAM = Pattern.compile(
            "([?&;](?:api_?key|apikey|key|token|access_token|auth|authorization|sig|signature|session|sid|"
                    + "secret|password|pass|hash|expires|policy|key-pair-id|x-amz-[a-z-]+)=)[^&#\\s]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_INFO = Pattern.compile("(https?://)[^/@\\s]+@");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+");

    private static volatile boolean verbose = true;

    private CigramLog() { }

    public static void setVerbose(boolean on) { verbose = on; }

    /** Removes secrets from a URL or a free text before it is logged or shown. */
    public static String redact(String text) {
        if (text == null) return "";
        String s = USER_INFO.matcher(text).replaceAll("$1***@");
        Matcher m = SECRET_PARAM.matcher(s);
        s = m.replaceAll("$1***");
        s = BEARER.matcher(s).replaceAll("$1 ***");
        return s;
    }

    public static void d(String tag, String message) {
        if (!verbose) return;
        write(3, tag, redact(message), null);
    }

    public static void w(String tag, String message, Throwable error) {
        write(5, tag, redact(message), error);
    }

    private static void write(int level, String tag, String message, Throwable error) {
        String full = error == null ? message : message + " (" + error.getClass().getSimpleName() + ": " + redact(String.valueOf(error.getMessage())) + ")";
        try {
            String t = "CigramEngine/" + tag;
            if (level >= 5) android.util.Log.w(t, full); else android.util.Log.d(t, full);
        } catch (Throwable notAndroid) {
            System.err.println(String.format(Locale.US, "[CigramEngine/%s] %s", tag, full));
        }
    }
}
