import { createClient } from "redis";

const REDIS_URL = process.env.REDIS_URL ?? "";
if (!REDIS_URL) {
  console.error("CUTVIDEO_SEED_PACK skipped: REDIS_URL missing");
  process.exit(0);
}

const id = "foot-20260807-2300";
const payload = {
  id,
  project: "Foot / Jusqu'au bout skano",
  created_at_millis: Date.now(),
  publications: [
    {
      order: 1,
      video_name: "CutVideo_1000130446_20260807_222236.mp4",
      account: "chknoirshadow",
      platform: "tiktok",
      date: "2026-08-07",
      time: "23:00",
      status: "a_programmer",
      visibility: "public",
      title: "Jusqu’au bout ⚽🔥",
      description: "Le foot jusqu’à la dernière seconde.",
      hashtags: ["#Foot", "#Motivation", "#offrespourtoi"]
    },
    {
      order: 2,
      video_name: "CutVideo_1000130456_20260807_221508.mp4",
      account: "chknoirshadow",
      platform: "tiktok",
      date: "2026-08-08",
      time: "18:00",
      status: "a_programmer",
      visibility: "public",
      title: "On ne lâche rien ⚽",
      description: "Chaque action compte jusqu’au bout.",
      hashtags: ["#Football", "#Mental", "#offrespourtoi"]
    }
  ]
};

const client = createClient({ url: REDIS_URL });
client.on("error", (error) => console.error("CUTVIDEO_SEED_PACK redis error", error));

try {
  await client.connect();
  await client.set(`cutvideo:pack:${id}`, JSON.stringify(payload), { EX: 7 * 24 * 60 * 60 });
  console.log(`CUTVIDEO_SEED_PACK ready https://cut-video-chatgpt-mcp.onrender.com/handoff-pack/${id}`);
} catch (error) {
  console.error("CUTVIDEO_SEED_PACK failed", error);
} finally {
  if (client.isOpen) await client.quit();
}
