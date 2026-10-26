// Cigram catalog proxy (Cloudflare Worker, module syntax).
//
// Why: the player engine searches movies / series by name through TMDB. The TMDB credential must not ship inside the
// app (anyone can unzip an APK), so the app talks to THIS worker and the worker talks to TMDB with the secret.
//
// What it is: a read-only, allow-listed mirror of five TMDB v3 GET routes. It cannot be used to reach any other TMDB
// endpoint, and it never forwards the caller's query string as is.
//
//   GET /3/search/multi?query=..&language=ar&page=1
//   GET /3/movie/{id}?append_to_response=external_ids,alternative_titles&language=ar
//   GET /3/tv/{id}?append_to_response=external_ids,alternative_titles&language=ar
//   GET /3/tv/{id}/season/{n}?language=ar
//
// Secrets (wrangler secret put):   TMDB_READ_TOKEN   (v4 "API Read Access Token")   or   TMDB_API_KEY (v3 key)
// Optional:                        APP_KEY           when set, requests must carry   X-Cigram-Key: <APP_KEY>
//                                  (a speed bump against casual abuse, not a security boundary: it ships in the app)
//
// TMDB attribution (required by their terms): "This product uses the TMDB API but is not endorsed or certified by TMDB."

const TMDB = "https://api.themoviedb.org";
const ROUTES = [
  { re: /^\/3\/search\/multi$/, ttl: 600, params: ["query", "language", "page"], required: ["query"] },
  { re: /^\/3\/(movie|tv)\/\d{1,9}$/, ttl: 3600, params: ["language", "append_to_response"] },
  { re: /^\/3\/tv\/\d{1,9}\/season\/\d{1,3}$/, ttl: 3600, params: ["language"] },
];
const APPEND_OK = new Set(["external_ids", "alternative_titles"]);

function json(status, body, extra) {
  return new Response(JSON.stringify(body), {
    status,
    headers: Object.assign({ "content-type": "application/json; charset=utf-8", "x-content-type-options": "nosniff" }, extra || {}),
  });
}

// Only known parameters, only valid values. Everything else is dropped.
export function cleanParams(params, allowed) {
  const out = new URLSearchParams();
  for (const k of allowed) {
    const v = params.get(k);
    if (v == null) continue;
    if (k === "query") {
      const q = v.trim();
      if (q.length < 1 || q.length > 100) return null;
      out.set("query", q);
    } else if (k === "language") {
      if (!/^[a-z]{2}(-[A-Z]{2})?$/.test(v)) return null;
      out.set("language", v);
    } else if (k === "page") {
      const n = Number(v);
      if (!Number.isInteger(n) || n < 1 || n > 5) return null;
      out.set("page", String(n));
    } else if (k === "append_to_response") {
      const parts = v.split(",").map((s) => s.trim()).filter(Boolean);
      if (parts.length === 0 || !parts.every((p) => APPEND_OK.has(p))) return null;
      out.set("append_to_response", parts.join(","));
    }
  }
  return out;
}

export default {
  async fetch(request, env, ctx) {
    if (request.method !== "GET" && request.method !== "HEAD") return json(405, { error: "method_not_allowed" }, { allow: "GET, HEAD" });
    if (env.APP_KEY && request.headers.get("x-cigram-key") !== env.APP_KEY) return json(401, { error: "app_key" });
    if (!env.TMDB_READ_TOKEN && !env.TMDB_API_KEY) return json(503, { error: "not_configured" });

    const url = new URL(request.url);
    const route = ROUTES.find((r) => r.re.test(url.pathname));
    if (!route) return json(404, { error: "not_found" });
    const params = cleanParams(url.searchParams, route.params);
    if (!params || (route.required || []).some((k) => !params.has(k))) return json(400, { error: "bad_params" });
    if (url.pathname === "/3/search/multi") params.set("include_adult", "false");

    const cacheKey = new Request(url.origin + url.pathname + "?" + params.toString(), { method: "GET" });
    const cache = typeof caches !== "undefined" ? caches.default : null;
    if (cache) {
      const hit = await cache.match(cacheKey);
      if (hit) return hit;
    }

    const upstream = new URL(TMDB + url.pathname);
    upstream.search = params.toString();
    const headers = { accept: "application/json" };
    if (env.TMDB_READ_TOKEN) headers.authorization = "Bearer " + String(env.TMDB_READ_TOKEN).replace(/^Bearer\s+/i, "");
    else upstream.searchParams.set("api_key", env.TMDB_API_KEY);

    let res;
    try {
      res = await fetch(upstream.toString(), { headers, cf: { cacheTtl: route.ttl, cacheEverything: true } });
    } catch (e) {
      return json(502, { error: "upstream_unreachable" });
    }
    // The caller must not learn (or be blamed for) our credential problems.
    if (res.status === 401 || res.status === 403) return json(502, { error: "upstream_auth" });
    if (res.status === 404) return json(404, { error: "not_found" });
    if (res.status === 429) return json(429, { error: "rate_limited" }, { "retry-after": res.headers.get("retry-after") || "10" });
    if (!res.ok) return json(502, { error: "upstream_" + res.status });

    const body = await res.text();
    const out = new Response(body, {
      status: 200,
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "public, max-age=" + route.ttl,
        "x-content-type-options": "nosniff",
      },
    });
    if (cache && ctx && ctx.waitUntil) ctx.waitUntil(cache.put(cacheKey, out.clone()));
    return out;
  },
};
