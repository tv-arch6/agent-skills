package com.Cigram.vid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The only HTTP client of the player engine.
 *
 * - Redirects are followed by hand (max 5): https is never downgraded to http, and credentials
 *   (Authorization / Cookie) are dropped when a redirect leaves the original host.
 * - Reads stop at {@link Request#maxBytes}; the connection is closed instead of draining the body,
 *   so probing a 4 GB video costs a few KB.
 * - Every connection is registered with the request's {@link CigramCancel}: cancelling ends a
 *   blocked read immediately.
 * - 4xx / 5xx are returned as a {@link Response} (callers decide); only network failures, timeouts
 *   and cancellation throw.
 */
final class CigramHttp {
    static final String UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final int MAX_REDIRECTS = 5;

    private CigramHttp() { }

    static final class Request {
        String url;
        String method = "GET";
        final HashMap<String, String> headers = new HashMap<String, String>();
        String range;                 // e.g. "bytes=0-4095"
        int maxBytes = 1 << 20;
        int connectMs = 8000;
        int readMs = 12000;
        boolean identityEncoding;     // media probes: no gzip, so byte offsets are real
        byte[] body;

        Request(String url) { this.url = url; }

        Request header(String k, String v) { if (k != null && v != null) headers.put(k, v); return this; }
        Request headers(Map<String, String> h) { if (h != null) for (Map.Entry<String, String> e : h.entrySet()) header(e.getKey(), e.getValue()); return this; }
        Request range(long from, long to) { range = "bytes=" + from + "-" + to; return this; }
        Request max(int n) { maxBytes = n; return this; }
        Request timeouts(int connect, int read) { connectMs = connect; readMs = read; return this; }
    }

    static final class Response {
        int code;
        String url;                   // final URL after redirects
        String contentType = "";
        String contentRange = "";
        byte[] body = new byte[0];
        boolean truncated;

        boolean ok() { return code >= 200 && code < 300; }

        String text() {
            return new String(body, charset());
        }

        Charset charset() {
            String t = contentType == null ? "" : contentType.toLowerCase(Locale.US);
            int i = t.indexOf("charset=");
            if (i >= 0) {
                String name = t.substring(i + 8).trim();
                int semi = name.indexOf(';');
                if (semi >= 0) name = name.substring(0, semi).trim();
                if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) name = name.substring(1, name.length() - 1);
                try { return Charset.forName(name); } catch (Throwable ignored) { }
            }
            return Charset.forName("UTF-8");
        }
    }

    static Response execute(Request r, CigramCancel cancel) throws CigramEngineException {
        String url = r.url;
        HashMap<String, String> headers = new HashMap<String, String>(r.headers);
        int hops = 0;
        while (true) {
            cancel.check();
            URL u;
            try {
                u = new URL(url);
            } catch (MalformedURLException e) {
                throw new CigramEngineException(CigramEngineException.PARSE, 0, "رابط غير صالح.", e);
            }
            String scheme = u.getProtocol();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new CigramEngineException(CigramEngineException.UNSUPPORTED, "نوع الرابط غير مدعوم.");
            }
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) u.openConnection();
                cancel.track(c);
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(r.connectMs);
                c.setReadTimeout(r.readMs);
                c.setRequestMethod(r.method);
                c.setRequestProperty("User-Agent", UA);
                if (r.identityEncoding) c.setRequestProperty("Accept-Encoding", "identity");
                if (r.range != null) c.setRequestProperty("Range", r.range);
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (safeHeader(e.getKey(), e.getValue())) c.setRequestProperty(e.getKey(), e.getValue());
                }
                if (r.body != null) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(r.body.length);
                    c.getOutputStream().write(r.body);
                }
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc != null && loc.length() > 0) {
                        if (++hops > MAX_REDIRECTS) {
                            throw new CigramEngineException(CigramEngineException.NETWORK, "تحويلات كثيرة جداً من المصدر.");
                        }
                        URL next = new URL(u, loc);
                        if ("https".equals(scheme) && "http".equals(next.getProtocol())) {
                            throw new CigramEngineException(CigramEngineException.UNSUPPORTED, "رُفض تحويل من اتصال آمن إلى غير آمن.");
                        }
                        if (!next.getHost().equalsIgnoreCase(u.getHost())) {
                            headers.remove("Authorization");
                            headers.remove("Cookie");
                        }
                        url = next.toString();
                        continue;
                    }
                }
                Response out = new Response();
                out.code = code;
                out.url = url;
                String ct = c.getContentType();
                out.contentType = ct == null ? "" : ct;
                String cr = c.getHeaderField("Content-Range");
                out.contentRange = cr == null ? "" : cr;
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(r.maxBytes, 16384));
                    byte[] chunk = new byte[8192];
                    int total = 0;
                    try {
                        while (total < r.maxBytes) {
                            int want = Math.min(chunk.length, r.maxBytes - total);
                            int n = in.read(chunk, 0, want);
                            if (n < 0) break;
                            buf.write(chunk, 0, n);
                            total += n;
                        }
                        if (total >= r.maxBytes && in.read() != -1) out.truncated = true;
                    } finally {
                        try { in.close(); } catch (Throwable ignored) { }
                    }
                    out.body = buf.toByteArray();
                }
                return out;
            } catch (CigramEngineException e) {
                throw e;
            } catch (SocketTimeoutException e) {
                if (cancel.isCancelled()) throw CigramEngineException.cancelled();
                throw new CigramEngineException(CigramEngineException.TIMEOUT, 0, "انتهت مهلة الاتصال بالمصدر.", e);
            } catch (IOException e) {
                if (cancel.isCancelled()) throw CigramEngineException.cancelled();
                throw new CigramEngineException(CigramEngineException.NETWORK, 0, "تعذر الاتصال بالمصدر.", e);
            } finally {
                if (c != null) {
                    cancel.untrack(c);
                    try { c.disconnect(); } catch (Throwable ignored) { }
                }
            }
        }
    }

    /** Header names / values with line breaks would let a hostile provider inject headers. */
    static boolean safeHeader(String k, String v) {
        if (k == null || v == null || k.length() == 0) return false;
        for (int i = 0; i < k.length(); i++) {
            char ch = k.charAt(i);
            if (ch <= 32 || ch >= 127 || ch == ':') return false;
        }
        return v.indexOf('\r') < 0 && v.indexOf('\n') < 0;
    }
}
