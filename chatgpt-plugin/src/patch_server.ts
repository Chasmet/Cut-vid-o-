import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

source = source.replace(
  '    "projet", "mon projet", "dernier projet", "projet actif", "chknoirshadow", "qg",\n',
  '    "projet", "mon projet", "dernier projet", "projet actif",\n',
);

source = source.replace(
  'description: "APPELLE CET OUTIL EN PREMIER dès que l\'utilisateur dit \'mes vidéos\', \'occupe-toi des vidéos\', cite un projet approximatif ou ne donne pas le nom exact du projet. Ne demande jamais une capture ni le nom du projet avant de l\'avoir appelé. Il retourne tous les projets et indique automatiquement le projet recommandé à traiter.",',
  'description: "APPELLE CET OUTIL EN PREMIER pour toute demande sur les vidéos. Il retourne TOUS les dossiers réels, TOUS les vrais noms de fichiers et les vidéos en attente. Parcours chaque dossier avec des vidéos à traiter, sans demander de capture ni de nom de projet. N\'utilise jamais d\'anciens noms mémorisés.",',
);

source = source.replace(
`      return {
        name: project.name,
        video_count: project.videos.length,
        pending_count: stats.pendingCount,
        latest_video_date_seconds: stats.latestAny,
        next_pending_video: stats.pendingVideos[0]?.name ?? "",
      };`,
`      return {
        name: project.name,
        video_count: project.videos.length,
        pending_count: stats.pendingCount,
        latest_video_date_seconds: stats.latestAny,
        next_pending_video: stats.pendingVideos[0]?.name ?? "",
        videos: project.videos,
        pending_videos: stats.pendingVideos,
      };`,
);

source = source.replace(
  'instruction: "Si l\'utilisateur n\'a pas donné de projet exact, utilise recommended_project sans lui poser de question.",',
  'instruction: "SOURCE DE VÉRITÉ : utilise uniquement les noms présents ici. Pour une demande générale, parcours TOUS les projets ayant pending_count > 0 et traite toutes leurs pending_videos. Ne demande pas get_cutvideo_work_context si ces données suffisent.",',
);

source = source.replace(
`          ...projects.map((p) => \`${'${p.name}'} — ${'${p.video_count}'} vidéos, ${'${p.pending_count}'} à traiter\`),`,
`          ...projects.flatMap((p) => [
            \`${'${p.name}'} — ${'${p.video_count}'} vidéos, ${'${p.pending_count}'} à traiter\`,
            ...p.videos.map((video, index) => \`  ${'${index + 1}'}. ${'${video.name}'}\`),
          ]),`,
);

source = source.replace(
  'description: "OUTIL PRINCIPAL DE LECTURE. Utilise-le pour toute demande du type \'occupe-toi de mes vidéos\', \'fais les metas\', \'programme mes vidéos\' ou quand le projet est flou. Le paramètre project est facultatif. Si absent, générique, égal à CHKNOIRSHADOW/QG ou incorrect, le serveur choisit automatiquement le projet récent qui a encore des vidéos à traiter. Ne demande jamais le nom du projet si cet outil peut le choisir.",',
  'description: "OUTIL DE LECTURE DÉTAILLÉ. Le paramètre project est facultatif. Si absent ou générique, sélectionne un dossier ayant des vidéos en attente. CHKNOIRSHADOW et QG peuvent être des noms réels de dossiers : ne les filtre jamais comme comptes. Utilise uniquement les noms de fichiers actuellement renvoyés par la bibliothèque.",',
);

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_MCP_PATCH applied: full library names + all-folder traversal");
