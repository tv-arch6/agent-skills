package com.Cigram.vid;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** A tiny local HTTP server for the tests: routes by path, optional Range support, hit counters. */
final class TestServer {
    static final class Reply {
        int code = 200;
        String type = "application/octet-stream";
        byte[] body = new byte[0];
        String location;
        boolean honourRange = true;
        CountDownLatch holdUntil;       // the handler blocks here before answering (timeouts / cancel tests)
        long delayMs;

        static Reply ok(String type, byte[] body) { Reply r = new Reply(); r.type = type; r.body = body; return r; }
        static Reply text(String type, String body) { return ok(type, body.getBytes(java.nio.charset.Charset.forName("UTF-8"))); }
        static Reply code(int c) { Reply r = new Reply(); r.code = c; r.type = "text/plain"; r.body = ("status " + c).getBytes(); return r; }
        static Reply redirect(String to) { Reply r = new Reply(); r.code = 302; r.location = to; return r; }
    }

    private final HttpServer server;
    private final ConcurrentHashMap<String, Reply> routes = new ConcurrentHashMap<String, Reply>();
    private final ConcurrentHashMap<String, AtomicInteger> hits = new ConcurrentHashMap<String, AtomicInteger>();
    final ConcurrentHashMap<String, String> lastAuth = new ConcurrentHashMap<String, String>();
    final ConcurrentHashMap<String, String> lastQuery = new ConcurrentHashMap<String, String>();
    final ConcurrentHashMap<String, String> lastReferer = new ConcurrentHashMap<String, String>();

    TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                String path = ex.getRequestURI().getPath();
                hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                if (auth != null) lastAuth.put(path, auth);
                String ref = ex.getRequestHeaders().getFirst("Referer");
                if (ref != null) lastReferer.put(path, ref);
                String q = ex.getRequestURI().getRawQuery();
                if (q != null) lastQuery.put(path, q);
                Reply r = routes.get(path);
                if (r == null) r = Reply.code(404);
                if (r.holdUntil != null) {
                    try { r.holdUntil.await(); } catch (InterruptedException ignored) { }
                }
                if (r.delayMs > 0) {
                    try { Thread.sleep(r.delayMs); } catch (InterruptedException ignored) { }
                }
                Headers h = ex.getResponseHeaders();
                h.set("Content-Type", r.type);
                if (r.location != null) h.set("Location", r.location);
                byte[] body = r.body;
                int code = r.code;
                String range = ex.getRequestHeaders().getFirst("Range");
                if (range != null && r.honourRange && code == 200 && range.startsWith("bytes=")) {
                    String[] p = range.substring(6).split("-");
                    int from = Integer.parseInt(p[0]);
                    int to = p.length > 1 && p[1].length() > 0 ? Integer.parseInt(p[1]) : body.length - 1;
                    to = Math.min(to, body.length - 1);
                    if (from <= to) {
                        byte[] part = new byte[to - from + 1];
                        System.arraycopy(body, from, part, 0, part.length);
                        body = part;
                        code = 206;
                        h.set("Content-Range", "bytes " + from + "-" + to + "/" + r.body.length);
                    }
                }
                ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    OutputStream os = ex.getResponseBody();
                    try { os.write(body); } catch (IOException ignored) { }
                    os.close();
                }
                ex.close();
            }
        });
        server.start();
    }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    void route(String path, Reply r) { routes.put(path, r); }

    int hits(String path) { AtomicInteger n = hits.get(path); return n == null ? 0 : n.get(); }

    void stop() { server.stop(0); }
}
