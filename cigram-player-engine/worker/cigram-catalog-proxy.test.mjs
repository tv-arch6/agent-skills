// node --test worker/cigram-catalog-proxy.test.mjs        (Node 18+; no dependencies, TMDB is replaced by a fake fetch)
import test from "node:test";
import assert from "node:assert/strict";
import worker, { cleanParams } from "./cigram-catalog-proxy.js";

function run(path, { env = { TMDB_READ_TOKEN: "tok" }, method = "GET", headers = {}, upstream } = {}) {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init });
    return upstream ? upstream(String(url), init) : new Response('{"ok":true}', { status: 200 });
  };
  return worker.fetch(new Request("https://proxy.example" + path, { method, headers }), env, { waitUntil() {} }).then((res) => ({ res, calls }));
}

test("search is forwarded with the secret in a header, adult content off, and only allowed params", async () => {
  const { res, calls } = await run("/3/search/multi?query=Dune&language=ar&page=2&api_key=EVIL&include_adult=true&x=1");
  assert.equal(res.status, 200);
  assert.equal(calls.length, 1);
  const u = new URL(calls[0].url);
  assert.equal(u.origin + u.pathname, "https://api.themoviedb.org/3/search/multi");
  assert.equal(u.searchParams.get("query"), "Dune");
  assert.equal(u.searchParams.get("include_adult"), "false");
  assert.equal(u.searchParams.get("api_key"), null);
  assert.equal(u.searchParams.get("x"), null);
  assert.equal(calls[0].init.headers.authorization, "Bearer tok");
});

test("only the allow-listed routes exist", async () => {
  for (const p of ["/3/person/1", "/3/discover/movie", "/3/movie/abc", "/3/movie/1/credits", "/3/tv/1/season/x", "/4/list/1", "/", "/3/search/person?query=a"]) {
    const { res, calls } = await run(p);
    assert.equal(res.status, 404, p);
    assert.equal(calls.length, 0, p);
  }
});

test("movie, tv and season routes pass; append_to_response is limited", async () => {
  assert.equal((await run("/3/movie/438631?append_to_response=external_ids,alternative_titles&language=ar")).res.status, 200);
  assert.equal((await run("/3/tv/1396")).res.status, 200);
  assert.equal((await run("/3/tv/1396/season/2?language=en-US")).res.status, 200);
  assert.equal((await run("/3/movie/1?append_to_response=credits")).res.status, 400);
});

test("bad parameters are refused before any upstream call", async () => {
  for (const p of ["/3/search/multi?query=", "/3/search/multi?query=" + "a".repeat(101), "/3/search/multi?query=a&page=9", "/3/search/multi?query=a&language=../../x", "/3/search/multi"]) {
    const { res, calls } = await run(p);
    assert.equal(res.status, 400, p);
    assert.equal(calls.length, 0, p);
  }
});

test("methods other than GET / HEAD are refused", async () => {
  assert.equal((await run("/3/tv/1", { method: "POST" })).res.status, 405);
  assert.equal((await run("/3/tv/1", { method: "DELETE" })).res.status, 405);
});

test("APP_KEY is enforced only when configured", async () => {
  const env = { TMDB_READ_TOKEN: "tok", APP_KEY: "k" };
  assert.equal((await run("/3/tv/1", { env })).res.status, 401);
  assert.equal((await run("/3/tv/1", { env, headers: { "x-cigram-key": "bad" } })).res.status, 401);
  assert.equal((await run("/3/tv/1", { env, headers: { "x-cigram-key": "k" } })).res.status, 200);
});

test("missing credentials give 503 without calling TMDB", async () => {
  const { res, calls } = await run("/3/tv/1", { env: {} });
  assert.equal(res.status, 503);
  assert.equal(calls.length, 0);
});

test("a v3 api key is used as a query parameter when there is no read token", async () => {
  const { calls } = await run("/3/tv/1", { env: { TMDB_API_KEY: "v3key" } });
  assert.equal(new URL(calls[0].url).searchParams.get("api_key"), "v3key");
  assert.equal(calls[0].init.headers.authorization, undefined);
});

test("upstream failures never leak the credential problem to the app", async () => {
  const cases = [[401, 502, "upstream_auth"], [403, 502, "upstream_auth"], [404, 404, "not_found"], [429, 429, "rate_limited"], [500, 502, "upstream_500"]];
  for (const [from, to, err] of cases) {
    const { res } = await run("/3/tv/1", { upstream: async () => new Response("secret details", { status: from }) });
    assert.equal(res.status, to);
    const body = await res.json();
    assert.equal(body.error, err);
  }
  const net = await run("/3/tv/1", { upstream: async () => { throw new Error("down"); } });
  assert.equal(net.res.status, 502);
});

test("cleanParams keeps nothing it was not asked for", () => {
  const p = cleanParams(new URLSearchParams("query=Hi&language=ar&evil=1"), ["query", "language"]);
  assert.equal(p.toString(), "query=Hi&language=ar");
});
