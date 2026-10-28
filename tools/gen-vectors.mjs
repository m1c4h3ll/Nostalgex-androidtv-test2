#!/usr/bin/env node
// Differential vectors from upstream's own JS engines, for the Kotlin port's tests.
//
//   NOSTALGEX_DIR=/path/to/chadMueller/Nostalgex node tools/gen-vectors.mjs
//
// filter.json:   upstream's scripts/nostalgex-channel-filter.cjs over the library the Roku
//                port's generator builds (roku/tests/gen-filter.mjs, MIT, upstream repo):
//                every real channel plus synthetic ones, a match and a near miss for each,
//                real manifest claims, random and edge-case items, under two pinned clocks.
// schedule.json: upstream's scripts/nostalgex-daily-manifest.js packing fourteen days in a
//                row for a varied pool (shows, films, sequels, premieres, non-ASCII keys) on
//                several channels in three zones, plus the guide and next picks.
//
// The vectors are generated, never edited. A mismatch is a bug in the port.
import { writeFileSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import vm from "node:vm";

const upstream = process.env.NOSTALGEX_DIR;
if (!upstream) { console.error("Set NOSTALGEX_DIR to a checkout of chadMueller/Nostalgex"); process.exit(2); }
const out = new URL("../engine/src/test/resources/vectors/", import.meta.url);

process.env.TZ = "UTC";
const { filterVectors } = await import(pathToFileURL(join(upstream, "roku/tests/gen-filter.mjs")).href);
const fv = filterVectors(upstream);
writeFileSync(new URL("filter.json", out), JSON.stringify({ generatedBy: "tools/gen-vectors.mjs", ...fv }));
console.log(`filter.json: ${fv.channels.length} channels x ${fv.items.length} items x ${Object.keys(fv.results).length} clocks`);

// ── Schedule ──
const src = readFileSync(join(upstream, "scripts/nostalgex-daily-manifest.js"), "utf8");
function loadEngine() {
  const store = new Map();
  const localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)); },
    removeItem: (k) => { store.delete(k); },
    key: (i) => [...store.keys()][i] ?? null,
    get length() { return store.size; },
  };
  const ctx = { Date, TextEncoder, localStorage };
  ctx.globalThis = ctx;
  vm.createContext(ctx);
  vm.runInContext(src, ctx);
  return ctx.NostalgexDailyManifest;
}
function prng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = prng(0xc0ffee);
const pick = (a) => a[Math.floor(rand() * a.length)];
const day0 = Date.UTC(2026, 9, 20) / 1000; // 2026-10-20
const items = [];
let k = 5000;
const shows = ["Cheers", "Frasier", "Seinfeld", "Twin Peaks", "Ålborg Nights", "日本の夜", "The X-Files"];
for (const show of shows) {
  const n = 3 + Math.floor(rand() * 20);
  for (let e = 0; e < n; e++) items.push({ ratingKey: String(k++), title: show, type: "episode", duration: pick([21, 22, 23, 24, 44, 45, 1]), addedAt: rand() < 0.1 ? day0 - Math.floor(rand() * 20) * 86400 : 1500000000 });
}
const films = ["Alien", "Heat", "Back to the Future Part II", "Back to the Future Part III", "Back to the Future", "Kill Bill: Part 1", "Kill Bill: Pt. 2", "Dune: Part Two", "Dune: Part 1", "Dune Part 2", "Amélie", "Ran", "Hausu", "Paris, Texas", "Harry Potter and the Deathly Hallows: Part 2", "Harry Potter and the Deathly Hallows: Part 1", "The Godfather Part II", "Shoah", "Satantango"];
for (const f of films) items.push({ ratingKey: (rand() < 0.15 ? "é" : "") + String(k++), title: f, type: "movie", duration: pick([88, 95, 102, 117, 133, 161, 202, 450, 1500]), addedAt: rand() < 0.3 ? day0 - Math.floor(rand() * 10) * 86400 : 1400000000 });
for (let i = 0; i < 60; i++) items.push({ ratingKey: String(k++), title: `Film ${i}`, type: "movie", duration: 80 + Math.floor(rand() * 70), addedAt: rand() < 0.2 ? day0 + 3600 - Math.floor(rand() * 9) * 86400 : 0 });
const pools = {
  shows: items.filter((i) => i.type === "episode"),
  films: items.filter((i) => i.type === "movie"),
  all: items,
  tiny: items.slice(0, 2),
  one: [items.find((i) => i.duration > 30)],
  mixed: items.filter((_, i) => i % 3 !== 1),
};
const channels = [
  { id: 2, pool: "shows" }, { id: 3, pool: "films" }, { id: 403, pool: "all", premiere: true },
  { id: 7, pool: "tiny" }, { id: 8, pool: "one" }, { id: 1000123, pool: "mixed", premiere: true }, { id: 55, pool: "all" },
];
const zones = ["America/Toronto", "Europe/Berlin", "Australia/Lord_Howe"];
const cases = [];
for (const zone of zones) {
  process.env.TZ = zone;
  const M = loadEngine();
  const lineup = channels.map((c) => ({ channel: { id: c.id, isPremiereChannel: !!c.premiere }, pool: pools[c.pool] }));
  const days = [];
  for (let d = 0; d < 14; d++) {
    const t = day0 + d * 86400 + 15 * 3600;
    const byCh = {};
    for (const { channel, pool } of lineup) byCh[channel.id] = M.getManifestBlocks(channel, pool, t * 1000, "vec").map((b) => [b.item.ratingKey, b.startSec, b.endSec]);
    days.push({ t, ch: byCh });
  }
  const at = [];
  for (let i = 0; i < 12; i++) {
    const t = day0 + 13 * 86400 + Math.floor(rand() * 86400) + 0.25;
    const raw = new Map();
    for (const { channel, pool } of lineup) { const s = M.buildSchedule(channel, pool, t, "vec"); if (s) raw.set(channel.id, s); }
    const shown = M.resolveConflicts(new Map(raw));
    const ch = {};
    for (const { channel, pool } of lineup) {
      const u = raw.get(channel.id), r = shown.get(channel.id);
      if (!u || !r) { ch[channel.id] = null; continue; }
      const tune = M.tuneIn(channel, pool, r, t, "vec");
      const prev = tune ? tune.item.ratingKey : null;
      const p = M.advancePick(channel, pool, prev, u.nowPlaying ? u.nowPlaying.endSec : t, lineup, "vec", true);
      ch[channel.id] = {
        now: u.nowPlaying ? u.nowPlaying.item.ratingKey : null, entries: u.entries.length, elapsed: u.elapsedSeconds,
        shown: r.nowPlaying ? r.nowPlaying.item.ratingKey : null,
        tune: tune ? [tune.item.ratingKey, tune.seekSeconds] : null, next: p ? [p.item.ratingKey, p.seekSeconds] : null,
      };
    }
    at.push({ t, ch });
  }
  cases.push({ zone, days, at });
}
writeFileSync(new URL("schedule.json", out), JSON.stringify({ generatedBy: "tools/gen-vectors.mjs", items, pools: Object.fromEntries(Object.entries(pools).map(([n, p]) => [n, p.map((i) => i.ratingKey)])), channels, cases }));
console.log(`schedule.json: ${items.length} items, ${channels.length} channels, ${zones.length} zones x 14 days`);

// ── Jellyfin/Emby library scan ──
// Upstream's own fetchJellyfinLibraryFromServer, lifted out of plex-tuner.html by the Roku
// port's generator and run over its saved server answers (roku/tests/fixtures).
{
  process.env.TZ = "UTC";
  new Function("globalThis", "window", "module", src)(globalThis, globalThis, undefined);
  const { mediaBrowserVectors } = await import(pathToFileURL(join(upstream, "roku/tests/gen-mediabrowser.mjs")).href);
  const mb = await mediaBrowserVectors(upstream);
  const fixture = JSON.parse(readFileSync(join(upstream, "roku/tests/fixtures/mediabrowser-server.json"), "utf8"));
  writeFileSync(new URL("mediabrowser.json", out), JSON.stringify({ generatedBy: "tools/gen-vectors.mjs", fixture, ...mb }));
  console.log(`mediabrowser.json: ${mb.items.length} items from ${mb.requests.length} requests`);
}
