#!/usr/bin/env node
// Collects the form structure of real pages with headless Chrome (fresh, empty profile).
// Reads URLs (one per line), writes one TSV line per visible input/select. Typed values are
// never read and nothing is submitted. Usage: node crawl.mjs urls.txt out.tsv [port]
import { spawn } from "node:child_process";
import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const [, , urlFile, outFile, portArg] = process.argv;
const port = Number(portArg || 9333);
const urls = readFileSync(urlFile, "utf8").split("\n").map((s) => s.trim()).filter((s) => s && !s.startsWith("#"));
const profile = mkdtempSync(join(tmpdir(), "m3b-crawl-"));
const chrome = spawn("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", [
  "--headless=new", `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, "--no-first-run",
  "--no-default-browser-check", "--disable-extensions", "--lang=de-DE", "--window-size=1280,1600", "about:blank",
], { stdio: "ignore" });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Runs in the page: every visible field with the label a browser would compute.
const EXTRACT = `(() => {
  const clean = (s) => (s || "").replace(/\\s+/g, " ").trim().slice(0, 120);
  const visible = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
    return r.width > 2 && r.height > 2 && cs.visibility !== "hidden" && cs.display !== "none" && cs.opacity !== "0"; };
  const labelOf = (el) => {
    if (el.labels && el.labels.length) return clean(el.labels[0].innerText);
    const lb = el.getAttribute("aria-labelledby");
    if (lb) return clean(lb.split(" ").map((id) => document.getElementById(id)?.innerText || "").join(" "));
    let node = el; for (let depth = 0; depth < 3 && node; depth++) {
      let sib = node.previousElementSibling;
      while (sib) { const t = clean(sib.innerText); if (t && !sib.querySelector("input,select,textarea")) return t; if (sib.querySelector("input,select,textarea")) break; sib = sib.previousElementSibling; }
      node = node.parentElement;
    }
    return "";
  };
  const skip = new Set(["hidden","submit","button","checkbox","radio","image","reset","file","range","color"]);
  const out = [];
  for (const el of document.querySelectorAll("input,select")) {
    const type = (el.getAttribute("type") || "text").toLowerCase();
    if (el.tagName === "INPUT" && skip.has(type)) continue;
    if (!visible(el)) continue;
    out.push({ kind: el.tagName === "SELECT" ? "select" : "input", type: el.tagName === "SELECT" ? "" : type,
      name: el.getAttribute("name") || "", id: el.id || "", autocomplete: el.getAttribute("autocomplete") || "",
      placeholder: el.getAttribute("placeholder") || "", aria: el.getAttribute("aria-label") || "", label: labelOf(el),
      maxlength: el.getAttribute("maxlength") || "", readonly: el.readOnly || el.disabled ? "1" : "",
      formidx: el.form ? [...document.forms].indexOf(el.form) : -1,
      formbuttons: el.form ? [...el.form.querySelectorAll("button,input[type=submit]")].filter(visible).map((b) => clean(b.innerText || b.value)).filter(Boolean).slice(0, 4).join(" | ") : "",
      options: el.tagName === "SELECT" ? [...el.options].slice(0, 40).map((o) => clean(o.text)).join("|") : "" });
  }
  const buttons = [...document.querySelectorAll("button,input[type=submit],[role=button]")].filter(visible).map((b) => clean(b.innerText || b.value)).filter(Boolean).slice(0, 8);
  return JSON.stringify({ fields: out, buttons, title: document.title, url: location.href });
})()`;

async function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  let id = 0; const pending = new Map();
  ws.onmessage = (m) => { const msg = JSON.parse(m.data); if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); } };
  return { send: (method, params = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); }), close: () => ws.close() };
}

const clean = (s) => String(s || "").replace(/[\t\n\r]+/g, " ").trim();
const header = ["form", "source", "kind", "type", "name", "id", "autocomplete", "placeholder", "aria", "label", "maxlength", "options", "readonly", "buttons", "formidx", "formbuttons"];
const rows = [header.join("\t")];

for (let tries = 0; tries < 50; tries++) { try { await fetch(`http://127.0.0.1:${port}/json/version`); break; } catch { await sleep(200); } }
let done = 0, withFields = 0;
const withTimeout = (p, ms) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error("timeout")), ms))]);

async function visit(url, index) {
  let target;
  try {
    target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: "PUT" })).json();
    const page = await cdp(target.webSocketDebuggerUrl);
    try {
      await page.send("Network.setUserAgentOverride", { userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36", acceptLanguage: "de-DE,de;q=0.9,en;q=0.8" });
      await page.send("Page.navigate", { url });
      await sleep(7000);
      const res = await withTimeout(page.send("Runtime.evaluate", { expression: EXTRACT, returnByValue: true }), 8000);
      const data = JSON.parse(res.result?.result?.value || "{\"fields\":[]}");
      const host = new URL(data.url || url).host;
      if (data.fields.length) withFields++;
      data.fields.forEach((f) => rows.push([`${host}#${index}`, host, f.kind, f.type, f.name, f.id, f.autocomplete, f.placeholder, f.aria, f.label, f.maxlength, f.options, f.readonly, data.buttons.join(" | "), String(f.formidx), f.formbuttons].map(clean).join("\t")));
    } finally {
      page.close();
    }
  } catch (e) {
    console.error("Fehler", url.slice(0, 60), String(e).slice(0, 60));
  } finally {
    if (target) await fetch(`http://127.0.0.1:${port}/json/close/${target.id}`).catch(() => {});
    done++;
    if (done % 20 === 0) console.log(`${done}/${urls.length} Seiten, ${withFields} mit Formular, ${rows.length - 1} Felder`);
  }
}

// Six tabs at a time; every page gets at most ~20 s.
const queue = urls.map((u, i) => [u, i]);
await Promise.all(Array.from({ length: 6 }, async () => {
  while (queue.length) {
    const [u, i] = queue.shift();
    await withTimeout(visit(u, i), 25000).catch(() => console.error("Abbruch", u.slice(0, 60)));
  }
}));
writeFileSync(outFile, rows.join("\n") + "\n");
console.log(`Fertig: ${done} Seiten, ${withFields} mit Formular, ${rows.length - 1} Felder`);
chrome.kill();
