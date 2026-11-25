// Viewer for secure links (Bitwarden Send, text type).
// The link looks like https://passwort.example/#/<accessId>/<key>. The part after "#" never
// leaves the browser: it holds the 16-byte key seed, everything is decrypted here.
"use strict";

const $ = (id) => document.getElementById(id);
const enc = new TextEncoder();

function b64ToBytes(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
function b64urlToBytes(s) {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  return b64ToBytes(s);
}
function bytesToB64(bytes) {
  let bin = "";
  bytes.forEach((b) => (bin += String.fromCharCode(b)));
  return btoa(bin);
}
function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}
async function hmac(key, data) {
  const k = await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", k, data));
}

// Bitwarden's shareable key: PRK = HMAC-SHA256("bitwarden-send", seed), then HKDF-Expand(PRK, "send", 64).
async function sendKeys(seed) {
  const prk = await hmac(enc.encode("bitwarden-send"), seed);
  const info = enc.encode("send");
  const t1 = await hmac(prk, concat(info, Uint8Array.of(1)));
  const t2 = await hmac(prk, concat(t1, info, Uint8Array.of(2)));
  const okm = concat(t1, t2);
  return { encKey: okm.slice(0, 32), macKey: okm.slice(32, 64) };
}

// EncString type 2: "2.<iv>|<ciphertext>|<mac>" (AES-256-CBC + HMAC-SHA256 over iv||ct).
async function decryptEncString(value, keys) {
  if (!value) return "";
  const dot = value.indexOf(".");
  if (value.slice(0, dot) !== "2") throw new Error("Unbekanntes Format");
  const [iv, ct, mac] = value.slice(dot + 1).split("|").map(b64ToBytes);
  const expected = await hmac(keys.macKey, concat(iv, ct));
  let diff = expected.length ^ mac.length;
  for (let i = 0; i < Math.min(expected.length, mac.length); i++) diff |= expected[i] ^ mac[i];
  if (diff !== 0) throw new Error("Prüfsumme falsch");
  const k = await crypto.subtle.importKey("raw", keys.encKey, "AES-CBC", false, ["decrypt"]);
  return new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-CBC", iv }, k, ct));
}

async function passwordHash(password, seed) {
  const k = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: seed, iterations: 100000 }, k, 256);
  return bytesToB64(new Uint8Array(bits));
}

function parseLink() {
  // Accepts "#/<accessId>/<key>" and Bitwarden's "#/send/<accessId>/<key>".
  const parts = location.hash.replace(/^#\/?/, "").split("/").filter(Boolean);
  if (parts[0] === "send") parts.shift();
  if (parts.length < 2) return null;
  try {
    const seed = b64urlToBytes(parts[1]);
    if (seed.length !== 16 || !/^[A-Za-z0-9_-]+$/.test(parts[0])) return null;
    return { accessId: parts[0], seed };
  } catch { return null; }
}

function showError(text) {
  const e = $("error");
  e.textContent = text;
  e.hidden = !text;
}

function fieldsFrom(text) {
  // The app sends {"m3b":1,"fields":[{"l":label,"v":value}]}; anything else is shown as one note.
  try {
    const data = JSON.parse(text);
    if (data && data.m3b === 1 && Array.isArray(data.fields)) {
      return data.fields.filter((f) => f && typeof f.v === "string" && f.v !== "").map((f) => ({ label: String(f.l || ""), value: f.v }));
    }
  } catch { /* plain text */ }
  return [{ label: "Inhalt", value: text }];
}

async function copy(text, button) {
  try {
    await navigator.clipboard.writeText(text);
    const old = button.textContent;
    button.textContent = "Kopiert";
    setTimeout(() => (button.textContent = old), 1500);
  } catch { showError("Kopieren ging nicht. Bitte markieren und von Hand kopieren."); }
}

function render(name, fields) {
  $("name").textContent = name;
  const box = $("fields");
  box.replaceChildren();
  for (const f of fields) {
    const row = document.createElement("div");
    row.className = "field";
    const text = document.createElement("div");
    text.className = "text";
    const label = document.createElement("div");
    label.className = "label";
    label.textContent = f.label;
    const value = document.createElement("div");
    value.className = "value";
    value.textContent = f.value;
    text.append(label, value);
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = "Kopieren";
    btn.addEventListener("click", () => copy(f.value, btn));
    row.append(text, btn);
    box.append(row);
  }
  $("copy-all").onclick = (ev) => copy(fields.map((f) => (f.label ? f.label + ": " : "") + f.value).join("\n"), ev.currentTarget);
  $("start").hidden = true;
  $("lead").hidden = true;
  $("result").hidden = false;
}

async function open(link) {
  const button = $("open");
  button.disabled = true;
  showError("");
  try {
    const pwShown = !$("pw-form").hidden;
    let query = "?id=" + encodeURIComponent(link.accessId);
    if (pwShown) {
      const pw = $("pw").value;
      if (!pw) { showError("Bitte das Passwort eingeben."); return; }
      query += "&h=" + encodeURIComponent(await passwordHash(pw, link.seed));
    }
    // Step 1: a short-lived token for this one link (checks password, expiry and view count).
    const tokenRes = await fetch("/send-token" + query, { method: "POST", credentials: "omit", cache: "no-store" });
    if (tokenRes.status === 429) { showError("Zu viele Versuche. Bitte warte eine Minute."); return; }
    if (!tokenRes.ok) {
      let kind = "";
      try { kind = (await tokenRes.json()).send_access_error_type || ""; } catch { /* no body */ }
      if (kind.startsWith("password_hash_b64")) {
        $("pw-form").hidden = false;
        $("pw").focus();
        showError(pwShown ? "Das Passwort stimmt nicht." : "");
        return;
      }
      showError("Dieser Link ist abgelaufen, wurde gelöscht oder schon zu oft geöffnet.");
      return;
    }
    const token = (await tokenRes.json()).access_token;
    // Step 2: the encrypted content (this counts as one view).
    const res = await fetch("/api/sends/access", {
      method: "POST",
      headers: { Authorization: "Bearer " + token },
      credentials: "omit",
      cache: "no-store",
    });
    if (res.status === 429) { showError("Zu viele Versuche. Bitte warte eine Minute."); return; }
    if (res.status === 404 || res.status === 400 || res.status === 401) {
      showError("Dieser Link ist abgelaufen, wurde gelöscht oder schon zu oft geöffnet.");
      return;
    }
    if (!res.ok) { showError("Der Link konnte gerade nicht geöffnet werden. Versuch es später noch einmal."); return; }
    const data = await res.json();
    const keys = await sendKeys(link.seed);
    const name = await decryptEncString(data.name || data.Name, keys);
    const textObj = data.text || data.Text;
    if (!textObj) { showError("Dieser Link enthält eine Datei. Das wird hier nicht unterstützt."); return; }
    const text = await decryptEncString(textObj.text || textObj.Text, keys);
    render(name, fieldsFrom(text));
  } catch (e) {
    showError("Der Link ist beschädigt oder unvollständig.");
  } finally {
    button.disabled = false;
  }
}

document.addEventListener("DOMContentLoaded", () => {
  const link = parseLink();
  if (!link || !window.crypto || !crypto.subtle) {
    $("start").hidden = true;
    showError(link ? "Dieser Browser kann den Link nicht entschlüsseln." : "Dieser Link ist unvollständig. Bitte den ganzen Link kopieren, auch den Teil nach dem #.");
    return;
  }
  $("open").addEventListener("click", () => open(link));
  $("pw-form").addEventListener("submit", (e) => { e.preventDefault(); open(link); });
});
