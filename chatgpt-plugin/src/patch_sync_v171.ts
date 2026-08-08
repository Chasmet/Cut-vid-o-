import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

// 1. Les installations ChatGPT qui ne voient que les 3 anciens outils doivent quand même pouvoir
// lire toute la bibliothèque. list_cutvideo_accounts devient donc aussi un lecteur de contexte.
const oldAccountsHandler = `  server.registerTool("list_cutvideo_accounts", {
    title: "Lister les comptes Cut Vidéo",
    description: "Liste les comptes. Ne pose pas de question si le compte manque : pour une demande générale, utilise CHKNOIRSHADOW par défaut, sauf si le contexte ou les programmations du projet indiquent QG.",
    inputSchema: {},
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async () => ({
    structuredContent: {
      default_account: "chknoirshadow",
      accounts: [
        { account: "chknoirshadow", label: "CHKNOIRSHADOW", platforms: ALLOWED.chknoirshadow },
        { account: "qg", label: "QG", platforms: ALLOWED.qg },
      ],
    },
    content: [{ type: "text", text: "Par défaut : CHKNOIRSHADOW. CHKNOIRSHADOW : YouTube, TikTok, Instagram, X. QG : YouTube, TikTok." }],
  }));`;

const newAccountsHandler = `  server.registerTool("list_cutvideo_accounts", {
    title: "Lire Cut Vidéo et ses comptes",
    description: "OUTIL DE COMPATIBILITÉ PRINCIPAL. Appelle-le au début d'une demande Cut Vidéo : il retourne les comptes ET toute la bibliothèque synchronisée avec les vrais dossiers, vrais noms de fichiers, vidéos en attente et projet recommandé. Il permet de travailler même si ChatGPT n'affiche que trois outils.",
    inputSchema: {},
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async () => {
    const library = await loadLibrary();
    const accounts = [
      { account: "chknoirshadow", label: "CHKNOIRSHADOW", platforms: ALLOWED.chknoirshadow },
      { account: "qg", label: "QG", platforms: ALLOWED.qg },
    ];
    if (!library) {
      return {
        structuredContent: {
          default_account: "chknoirshadow",
          accounts,
          library_available: false,
          instruction: "La bibliothèque n'est pas encore synchronisée. Ouvre Cut Vidéo et attends le message Synchro ChatGPT OK.",
        },
        content: [{ type: "text", text: "Comptes disponibles, mais bibliothèque non synchronisée. Attendre le message Synchro ChatGPT OK dans l'APK." }],
      };
    }

    const recommended = recommendedProject(library);
    const projects = library.projects.map((project) => {
      const stats = projectStats(library, project);
      return {
        name: project.name,
        video_count: project.videos.length,
        pending_count: stats.pendingCount,
        videos: project.videos,
        pending_videos: stats.pendingVideos,
        existing_schedules: stats.schedules,
        recommended_defaults: recommendedDefaults(library, project),
      };
    });
    return {
      structuredContent: {
        default_account: "chknoirshadow",
        accounts,
        library_available: true,
        synced_at_millis: library.synced_at_millis,
        app_version: library.app_version,
        project_count: projects.length,
        total_videos: projects.reduce((sum, item) => sum + item.video_count, 0),
        recommended_project: recommended?.name ?? "",
        projects,
        instruction: "SOURCE DE VÉRITÉ. Pour 'fais mes metas' ou 'programme mes vidéos', utilise directement les pending_videos réelles de tous les projets concernés, sans demander de noms de fichiers ni de capture.",
      },
      content: [{
        type: "text",
        text: [
          \`CUT VIDÉO — bibliothèque synchronisée par APK ${'${library.app_version}'}\`,
          \`Projet recommandé : ${'${recommended?.name ?? "aucun"}'}\`,
          ...projects.flatMap((item) => [
            \`${'${item.name}'} — ${'${item.video_count}'} vidéos / ${'${item.pending_count}'} à traiter\`,
            ...item.pending_videos.map((video, index) => \`  ${'${index + 1}'}. ${'${video.name}'}\`),
          ]),
        ].join("\\n"),
      }],
    };
  });`;

if (source.includes(oldAccountsHandler)) {
  source = source.replace(oldAccountsHandler, newAccountsHandler);
} else if (!source.includes('library_available: true')) {
  throw new Error("CUTVIDEO 2.1.1 patch: list_cutvideo_accounts handler not found");
}

// 2. Une synchro rapide de bibliothèque ne doit pas effacer les frames déjà valides côté serveur.
const oldLibrarySave = `    const client = await getRedis();
    await client.set(LIBRARY_KEY, JSON.stringify(parsed.data));
    const recommended = recommendedProject(parsed.data);`;
const newLibrarySave = `    const client = await getRedis();
    const incomingLibrary = parsed.data;
    try {
      const previousRaw = await client.get(LIBRARY_KEY);
      if (previousRaw) {
        const previousParsed = librarySchema.safeParse(JSON.parse(previousRaw));
        if (previousParsed.success) {
          for (const incomingProject of incomingLibrary.projects) {
            const previousProject = previousParsed.data.projects.find(
              (candidate) => normalizeName(candidate.name) === normalizeName(incomingProject.name),
            );
            if (!previousProject) continue;
            for (const incomingVideo of incomingProject.videos) {
              if (incomingVideo.frame_urls.length || incomingVideo.thumbnail_url) continue;
              const previousVideo = previousProject.videos.find(
                (candidate) => normalizeName(candidate.name) === normalizeName(incomingVideo.name)
                  && candidate.duration_ms === incomingVideo.duration_ms
                  && candidate.size_bytes === incomingVideo.size_bytes,
              );
              if (!previousVideo) continue;
              incomingVideo.thumbnail_url = previousVideo.thumbnail_url;
              incomingVideo.frame_urls = previousVideo.frame_urls;
              if (!incomingVideo.transcript && previousVideo.transcript) incomingVideo.transcript = previousVideo.transcript;
            }
          }
        }
      }
    } catch (error) {
      console.error("Previous media merge skipped", error);
    }
    await client.set(LIBRARY_KEY, JSON.stringify(incomingLibrary));
    const recommended = recommendedProject(incomingLibrary);`;

if (source.includes(oldLibrarySave)) {
  source = source.replace(oldLibrarySave, newLibrarySave);
} else if (!source.includes("Previous media merge skipped")) {
  throw new Error("CUTVIDEO 2.1.1 patch: library save block not found");
}

// Les compteurs de la réponse de synchro doivent utiliser la bibliothèque effectivement sauvegardée.
source = source.replaceAll("parsed.data.synced_at_millis", "incomingLibrary.synced_at_millis");
source = source.replaceAll("parsed.data.projects.length", "incomingLibrary.projects.length");
source = source.replaceAll("parsed.data.projects.reduce", "incomingLibrary.projects.reduce");

// 3. Dès qu'un lot de frames arrive, on rattache immédiatement ses URLs à la vidéo dans la
// bibliothèque Redis. Les images deviennent donc disponibles progressivement, sans second snapshot.
const frameResponseMarker = `    res.json({
      ok: true,
      project,
      video_name: videoName,
      frame_count: frameUrls.length,`;
const frameResponseReplacement = `    try {
      const libraryRaw = await client.get(LIBRARY_KEY);
      if (libraryRaw && frameUrls.length) {
        const parsedLibrary = librarySchema.safeParse(JSON.parse(libraryRaw));
        if (parsedLibrary.success) {
          const library = parsedLibrary.data;
          const normalizedProject = normalizeName(project);
          const normalizedVideo = normalizeName(videoName);
          const preferredProject = library.projects.find(
            (candidate) => normalizeName(candidate.name) === normalizedProject,
          );
          const candidateProjects = preferredProject ? [preferredProject] : library.projects;
          let updated = false;
          for (const candidateProject of candidateProjects) {
            const targetVideo = candidateProject.videos.find(
              (candidate) => normalizeName(candidate.name) === normalizedVideo,
            );
            if (!targetVideo) continue;
            targetVideo.thumbnail_url = frameUrls[0] ?? "";
            targetVideo.frame_urls = frameUrls;
            updated = true;
            break;
          }
          if (updated) await client.set(LIBRARY_KEY, JSON.stringify(library));
        }
      }
    } catch (error) {
      console.error("Frame library attachment failed", error);
    }

    res.json({
      ok: true,
      project,
      video_name: videoName,
      frame_count: frameUrls.length,`;

if (source.includes(frameResponseMarker)) {
  source = source.replace(frameResponseMarker, frameResponseReplacement);
} else if (!source.includes("Frame library attachment failed")) {
  throw new Error("CUTVIDEO 2.1.1 patch: frame response block not found");
}

source = source.replaceAll('version: "2.1.0"', 'version: "2.1.1"');
source = source.replaceAll("Cut Vidéo MCP v2.1.0 listening on port", "Cut Vidéo MCP v2.1.1 listening on port");

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_SYNC_171_PATCH applied: instant library + progressive frames + 3-tool compatibility");
