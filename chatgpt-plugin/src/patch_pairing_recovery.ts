import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

const pairStart = source.indexOf('app.post("/api/library/pair", async (req, res) => {');
const syncStart = source.indexOf('app.post("/api/library/sync", async (req, res) => {');

if (pairStart < 0 || syncStart < 0 || syncStart <= pairStart) {
  throw new Error("CUTVIDEO pairing recovery patch: pair/sync route markers not found");
}

const replacement = `app.post("/api/library/pair", async (req, res) => {
  const token = typeof req.body?.device_token === "string" ? req.body.device_token.trim() : "";
  const appVersion = typeof req.body?.app_version === "string" ? req.body.app_version.trim() : "";
  if (token.length < 24 || token.length > 200) {
    res.status(400).json({ error: "Invalid device token" });
    return;
  }
  try {
    const client = await getRedis();
    const stored = await client.get(DEVICE_TOKEN_KEY);

    if (!stored) {
      await client.set(DEVICE_TOKEN_KEY, token);
      res.json({ ok: true, paired: true, recovered: false, app_version: appVersion });
      return;
    }

    if (stored === token) {
      res.json({ ok: true, paired: true, recovered: false, app_version: appVersion });
      return;
    }

    const TAKEOVER_IDLE_MS = 5 * 60 * 1000;
    let lastSyncMillis = 0;
    try {
      const rawLibrary = await client.get(LIBRARY_KEY);
      if (rawLibrary) {
        const parsedLibrary = librarySchema.safeParse(JSON.parse(rawLibrary));
        if (parsedLibrary.success) {
          lastSyncMillis = Math.max(0, parsedLibrary.data.synced_at_millis);
        }
      }
    } catch (error) {
      console.error("Pairing activity check skipped", error);
    }

    const idleForMillis = lastSyncMillis > 0 ? Math.max(0, Date.now() - lastSyncMillis) : Number.MAX_SAFE_INTEGER;
    if (idleForMillis >= TAKEOVER_IDLE_MS) {
      await client.set(DEVICE_TOKEN_KEY, token);
      console.log("CUTVIDEO_PAIRING recovered stale device token", {
        app_version: appVersion,
        idle_for_seconds: Math.floor(idleForMillis / 1000),
      });
      res.json({
        ok: true,
        paired: true,
        recovered: true,
        app_version: appVersion,
        previous_device_idle_seconds: Math.floor(idleForMillis / 1000),
      });
      return;
    }

    res.status(403).json({
      error: "Another Cut Vidéo device is currently active",
      retry_after_seconds: Math.max(1, Math.ceil((TAKEOVER_IDLE_MS - idleForMillis) / 1000)),
    });
  } catch (error) {
    console.error("Pairing failed", error);
    res.status(503).json({ error: "Cut Vidéo storage unavailable" });
  }
});

`;

source = source.slice(0, pairStart) + replacement + source.slice(syncStart);
source = source.replaceAll('version: "2.2.0"', 'version: "2.2.1"');
source = source.replaceAll("Cut Vidéo MCP v2.2.0 listening on port", "Cut Vidéo MCP v2.2.1 listening on port");

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_PAIRING_RECOVERY_PATCH applied: stale token recovery after reinstall");
