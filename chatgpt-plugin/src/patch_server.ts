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

// Accepte dès maintenant les futurs champs d'analyse média. Les anciennes APK qui ne les
// envoient pas continuent de fonctionner grâce aux valeurs par défaut.
source = source.replace(
`  date_added_seconds: z.number().int().nonnegative().default(0),
});`,
`  date_added_seconds: z.number().int().nonnegative().default(0),
  thumbnail_url: z.string().trim().max(4000).default(""),
  frame_urls: z.array(z.string().trim().max(4000)).max(12).default([]),
  transcript: z.string().max(30000).default(""),
});`,
);

// Compatibilité avec les installations ChatGPT qui n'exposent encore que les 3 anciens outils.
// Un appel du pack avec le marqueur __CUTVIDEO_LIBRARY__ devient un appel de lecture et renvoie
// toute la bibliothèque synchronisée sans créer de lot de publication.
source = source.replace(
`    if (!library) throw new Error("Ouvre d'abord Cut Vidéo quelques secondes afin de synchroniser la bibliothèque.");
    const selected = selectProject(library, project, publications.map((publication) => publication.video_name));`,
`    if (!library) throw new Error("Ouvre d'abord Cut Vidéo quelques secondes afin de synchroniser la bibliothèque.");
    const compatibilityRead = project === "__CUTVIDEO_LIBRARY__"
      || publications.some((publication) => publication.video_name === "__CUTVIDEO_LIBRARY__");
    if (compatibilityRead) {
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
          mode: "library_read_compat",
          synced_at_millis: library.synced_at_millis,
          app_version: library.app_version,
          project_count: projects.length,
          total_videos: projects.reduce((sum, item) => sum + item.video_count, 0),
          projects,
          instruction: "Parcours tous les dossiers ayant pending_count > 0, utilise uniquement pending_videos, crée une méta différente par vidéo puis appelle prepare_cutvideo_publication_pack normalement pour chaque dossier.",
        },
        content: [{
          type: "text",
          text: [
            "BIBLIOTHÈQUE CUT VIDÉO — MODE COMPATIBILITÉ",
            ...projects.flatMap((item) => [
              \`${'${item.name}'} — ${'${item.video_count}'} vidéos, ${'${item.pending_count}'} à traiter\`,
              ...item.pending_videos.map((video, index) => \`  ${'${index + 1}'}. ${'${video.name}'}\`),
            ]),
          ].join("\\n"),
        }],
      };
    }
    const selected = selectProject(library, project, publications.map((publication) => publication.video_name));`,
);

// Ajoute les outils MCP attendus par le workflow complet. Ils sont injectés avant le return
// de createServer afin de rester compatibles avec le serveur existant et son stockage Redis.
if (!source.includes('server.registerTool("get_cutvideo_folder"')) {
  source = source.replace(
`  return server;
}`,
`  const resolveVideoEntry = (library: LibrarySnapshot, requestedProject: string, requestedVideo: string) => {
    const target = normalizeName(requestedVideo);
    if (!target) throw new Error("Nom de vidéo manquant.");

    let projects = library.projects;
    if (requestedProject && !isGenericProjectName(requestedProject)) {
      const normalizedProject = normalizeName(requestedProject);
      const filtered = library.projects.filter((project) => {
        const name = normalizeName(project.name);
        return name === normalizedProject || name.includes(normalizedProject) || normalizedProject.includes(name);
      });
      if (filtered.length) projects = filtered;
    }

    let matches = projects.flatMap((project) =>
      project.videos
        .filter((video) => normalizeName(video.name) === target)
        .map((video) => ({ project, video })),
    );

    if (!matches.length) {
      matches = projects.flatMap((project) =>
        project.videos
          .filter((video) => {
            const name = normalizeName(video.name);
            return name.includes(target) || target.includes(name);
          })
          .map((video) => ({ project, video })),
      );
    }

    if (!matches.length) throw new Error(\`Vidéo introuvable : ${'${requestedVideo}'}\`);
    if (matches.length > 1) {
      throw new Error(\`Vidéo ambiguë : ${'${requestedVideo}'}. Dossiers possibles : ${'${matches.map((match) => match.project.name).join(", ")}'}\`);
    }
    return matches[0];
  };

  server.registerTool("get_cutvideo_folder", {
    title: "Lire un dossier Cut Vidéo",
    description: "Retourne un dossier réel, toutes ses vidéos, les vidéos encore à traiter, les programmations existantes et les réglages recommandés. Le nom peut être un chemin Classement / Dossier.",
    inputSchema: {
      folder: z.string().trim().max(200).default(""),
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async ({ folder }) => {
    const library = await loadLibrary();
    if (!library) throw new Error("La bibliothèque Cut Vidéo n'est pas synchronisée.");
    const selected = selectProject(library, folder);
    const stats = projectStats(library, selected.project);
    return {
      structuredContent: {
        folder: selected.project.name,
        selection_method: selected.method,
        videos: selected.project.videos,
        pending_videos: stats.pendingVideos,
        existing_schedules: stats.schedules,
        recommended_defaults: recommendedDefaults(library, selected.project),
      },
      content: [{
        type: "text",
        text: [
          \`Dossier : ${'${selected.project.name}'}\`,
          \`À traiter : ${'${stats.pendingCount}'}\`,
          ...stats.pendingVideos.map((video, index) => \`${'${index + 1}'}. ${'${video.name}'}\`),
        ].join("\\n"),
      }],
    };
  });

  server.registerTool("get_cutvideo_video", {
    title: "Lire une vidéo Cut Vidéo",
    description: "Recherche une vidéo réelle dans toute la bibliothèque et retourne son dossier, ses informations locales et ses programmations.",
    inputSchema: {
      project: z.string().trim().max(200).default(""),
      video_name: z.string().trim().min(1).max(300),
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async ({ project, video_name }) => {
    const library = await loadLibrary();
    if (!library) throw new Error("La bibliothèque Cut Vidéo n'est pas synchronisée.");
    const entry = resolveVideoEntry(library, project, video_name);
    const schedules = schedulesForProject(library, entry.project)
      .filter((schedule) => normalizeName(schedule.video_name) === normalizeName(entry.video.name));
    return {
      structuredContent: {
        project: entry.project.name,
        video: entry.video,
        schedules,
      },
      content: [{ type: "text", text: \`${'${entry.project.name}'} / ${'${entry.video.name}'}\` }],
    };
  });

  server.registerTool("analyze_cutvideo_video", {
    title: "Analyser une vidéo Cut Vidéo",
    description: "Retourne les éléments réellement disponibles pour analyser une vidéo : nom, durée, miniature, frames et transcription si l'APK les a synchronisés. N'invente jamais une analyse visuelle si ces éléments sont absents.",
    inputSchema: {
      project: z.string().trim().max(200).default(""),
      video_name: z.string().trim().min(1).max(300),
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async ({ project, video_name }) => {
    const library = await loadLibrary();
    if (!library) throw new Error("La bibliothèque Cut Vidéo n'est pas synchronisée.");
    const entry = resolveVideoEntry(library, project, video_name);
    const hasThumbnail = Boolean(entry.video.thumbnail_url);
    const hasFrames = entry.video.frame_urls.length > 0;
    const hasTranscript = Boolean(entry.video.transcript.trim());
    const analysisAvailable = hasThumbnail || hasFrames || hasTranscript;
    return {
      structuredContent: {
        project: entry.project.name,
        video: entry.video,
        analysis_available: analysisAvailable,
        media_available: {
          thumbnail: hasThumbnail,
          frames: hasFrames,
          transcript: hasTranscript,
        },
        instruction: analysisAvailable
          ? "Analyse uniquement les éléments média réellement fournis avec cette vidéo."
          : "Analyse limitée au nom, à la durée, à la taille et au dossier. L'APK doit synchroniser miniature/frames/transcription pour une analyse réelle du contenu.",
      },
      content: [{
        type: "text",
        text: analysisAvailable
          ? \`Média disponible pour ${'${entry.video.name}'} : miniature=${'${hasThumbnail}'}, frames=${'${hasFrames}'}, transcription=${'${hasTranscript}'}\`
          : \`Aucune frame ni transcription synchronisée pour ${'${entry.video.name}'}.\`,
      }],
    };
  });

  server.registerTool("save_cutvideo_publication_plan", {
    title: "Enregistrer un plan de publication Cut Vidéo",
    description: "Enregistre un plan complet à partir des vrais fichiers synchronisés et produit un lien d'import Cut Vidéo. Utilise une méta différente par vidéo.",
    inputSchema: {
      project: z.string().trim().max(200).default(""),
      timezone: z.string().trim().default("Europe/Paris"),
      notes: z.string().trim().max(3000).default(""),
      publications: z.array(publicationSchema).min(1).max(500),
    },
    annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false, idempotentHint: false },
  }, async ({ project, timezone, notes, publications }) => {
    const library = await loadLibrary();
    if (!library) throw new Error("La bibliothèque Cut Vidéo n'est pas synchronisée.");
    const selected = selectProject(library, project, publications.map((publication) => publication.video_name));
    const realNames = new Set(selected.project.videos.map((video) => normalizeName(video.name)));
    for (const publication of publications) {
      if (!realNames.has(normalizeName(publication.video_name))) {
        throw new Error(\`La vidéo ${'${publication.video_name}'} n'existe pas dans ${'${selected.project.name}'}.\`);
      }
    }
    const ordered = [...publications].sort((a, b) => a.order - b.order).map(preparePublication);
    const pack = await savePack(selected.project.name, ordered);
    return {
      structuredContent: {
        project: selected.project.name,
        timezone,
        notes,
        publications: ordered,
        batch_id: pack.id,
        import_all_url: pack.import_url,
      },
      content: [{ type: "text", text: \`Plan enregistré : ${'${ordered.length}'} publication(s) pour ${'${selected.project.name}'}. Import : ${'${pack.import_url}'}\` }],
    };
  });

  server.registerTool("get_cutvideo_schedule", {
    title: "Lire les programmations Cut Vidéo",
    description: "Retourne les programmations déjà synchronisées, pour un dossier précis ou pour toute la bibliothèque.",
    inputSchema: {
      project: z.string().trim().max(200).default(""),
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async ({ project }) => {
    const library = await loadLibrary();
    if (!library) throw new Error("La bibliothèque Cut Vidéo n'est pas synchronisée.");
    let schedules = library.schedules;
    let resolvedProject = "";
    if (project && !isGenericProjectName(project)) {
      const selected = selectProject(library, project);
      resolvedProject = selected.project.name;
      schedules = schedulesForProject(library, selected.project);
    }
    schedules = [...schedules].sort((a, b) => a.scheduled_at_millis - b.scheduled_at_millis);
    return {
      structuredContent: {
        project: resolvedProject,
        count: schedules.length,
        schedules,
      },
      content: [{
        type: "text",
        text: schedules.length
          ? schedules.map((schedule, index) => \`${'${index + 1}'}. ${'${schedule.video_name}'} — ${'${schedule.platform}'} — ${'${new Date(schedule.scheduled_at_millis).toISOString()}'}\`).join("\\n")
          : "Aucune programmation.",
      }],
    };
  });

  return server;
}`,
  );
}

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_MCP_PATCH applied: full library + compatibility reader + full MCP toolset");
