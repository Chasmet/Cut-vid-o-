import { createClient } from "redis";

const REDIS_URL = process.env.REDIS_URL ?? "";
const PAIRING_EPOCH = process.env.PAIRING_EPOCH ?? "";
const DEVICE_TOKEN_KEY = "cutvideo:device-token:v1";
const PAIRING_EPOCH_KEY = "cutvideo:pairing-epoch:v1";
const LIBRARY_KEY = "cutvideo:library:v1";

async function main() {
  if (!REDIS_URL) {
    console.error("CUTVIDEO_WATCH Redis not configured");
    return;
  }

  const client = createClient({ url: REDIS_URL });
  client.on("error", (error) => console.error("CUTVIDEO_WATCH Redis error", error));
  await client.connect();

  if (PAIRING_EPOCH) {
    const storedEpoch = await client.get(PAIRING_EPOCH_KEY);
    if (storedEpoch !== PAIRING_EPOCH) {
      await client.del(DEVICE_TOKEN_KEY);
      await client.set(PAIRING_EPOCH_KEY, PAIRING_EPOCH);
      console.log(`CUTVIDEO_PAIR_RESET epoch=${PAIRING_EPOCH}`);
    }
  }

  let previous = "";
  const inspect = async () => {
    const raw = await client.get(LIBRARY_KEY);
    if (!raw || raw === previous) return;
    previous = raw;
    try {
      const parsed = JSON.parse(raw);
      const projects = Array.isArray(parsed?.projects) ? parsed.projects : [];
      const summary = projects.map((project: any) => ({
        name: String(project?.name ?? ""),
        videos: Array.isArray(project?.videos)
          ? project.videos.map((video: any) => String(video?.name ?? ""))
          : [],
      }));
      console.log("CUTVIDEO_LIBRARY", JSON.stringify({
        app_version: String(parsed?.app_version ?? ""),
        synced_at_millis: Number(parsed?.synced_at_millis ?? 0),
        projects: summary,
        schedules: Array.isArray(parsed?.schedules) ? parsed.schedules.length : 0,
      }));
    } catch (error) {
      console.error("CUTVIDEO_WATCH invalid library", error);
    }
  };

  await inspect();
  setInterval(() => void inspect().catch((error) => console.error("CUTVIDEO_WATCH inspect failed", error)), 3000);
}

void main().catch((error) => console.error("CUTVIDEO_WATCH failed", error));
