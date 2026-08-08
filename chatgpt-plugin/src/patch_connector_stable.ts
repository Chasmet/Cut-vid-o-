import { readFile, writeFile } from "node:fs/promises";

const path = new URL("./server_auto.ts", import.meta.url);
let source = await readFile(path, "utf8");

if (!source.includes("function createFullServer(): McpServer")) {
  source = source.replace(
    "function createServer(): McpServer {",
    "function createFullServer(): McpServer {",
  );
}

const stableServer = String.raw`
function createStableServer(): McpServer {
  const server = new McpServer({ name: "cut-video-stable-connector", version: "2.2.0" });

  server.registerTool("list_cutvideo_accounts", {
    title: "Lister les comptes Cut Vidéo",
    description: "Use this only when the user has not specified the Cut Vidéo account. CHKNOIRSHADOW can use YouTube, TikTok, Instagram and X. QG can use YouTube and TikTok.",
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
        structuredContent: { default_account: "chknoirshadow", accounts, library_available: false },
        content: [{ type: "text", text: "Bibliothèque Cut Vidéo non synchronisée." }],
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
        app_version: library.app_version,
        synced_at_millis: library.synced_at_millis,
        project_count: projects.length,
        total_videos: projects.reduce((sum, item) => sum + item.video_count, 0),
        recommended_project: recommended?.name ?? "",
        projects,
        instruction: "SOURCE DE VERITE: utilise les pending_videos et leurs vrais noms sans demander de capture ni de noms a l'utilisateur.",
      },
      content: [{
        type: "text",
        text: [
          \`CUT VIDEO — APK \${library.app_version}\`,
          \`Projet recommande: \${recommended?.name ?? "aucun"}\`,
          ...projects.flatMap((item) => [
            \`\${item.name} — \${item.video_count} videos / \${item.pending_count} a traiter\`,
            ...item.pending_videos.map((video, index) => \`  \${index + 1}. \${video.name}\`),
          ]),
        ].join("\n"),
      }],
    };
  });

  server.registerTool("prepare_cutvideo_metadata", {
    title: "Créer les métadonnées d'une vidéo",
    description: "Use this when the user asks ChatGPT to prepare metadata for one Cut Vidéo file. The metadata must describe the supplied video filename/topic, be suitable for the selected network, use at most 5 hashtags, and the complete title + description + hashtags block must be 100 characters maximum. Do not invent an unrelated subject.",
    inputSchema: {
      video_name: z.string().trim().min(1).max(250),
      account: accountSchema,
      platform: platformSchema,
      title: z.string().trim().min(1).max(70),
      description: z.string().trim().max(90).default(""),
      hashtags: z.array(z.string()).max(5).default([]),
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false, idempotentHint: true },
  }, async ({ video_name, account, platform, title, description, hashtags }) => {
    assertAllowed(account, platform);
    const tags = normalizeHashtags(hashtags);
    const metadata = compactMetadata(title, description, tags);
    return {
      structuredContent: {
        video_name, account, account_label: accountLabel(account), platform,
        title: title.trim(), description: description.trim(), hashtags: tags,
        metadata_text: metadata, metadata_characters: [...metadata].length,
        max_characters: MAX_METADATA_CHARS,
      },
      content: [{ type: "text", text: metadata }],
    };
  });

  const legacyPublicationSchema = z.object({
    order: z.number().int().min(1).max(999),
    video_name: z.string().trim().min(1).max(250),
    account: accountSchema,
    platform: platformSchema,
    date: z.string().trim().min(8).max(20),
    time: z.string().trim().min(4).max(10),
    status: z.enum(["a_programmer", "programme", "a_publier", "publie"]).default("a_programmer"),
    visibility: z.string().trim().max(80).default(""),
    title: z.string().trim().min(1).max(70),
    description: z.string().trim().max(90).default(""),
    hashtags: z.array(z.string()).max(5).default([]),
  });

  server.registerTool("prepare_cutvideo_publication_pack", {
    title: "Programmer un lot Cut Vidéo",
    description: "Use this as the main Cut Vidéo tool. For every supplied video file, create network-specific metadata that matches that file, stays within 100 characters total, then organize the requested date/time schedule and return one clean fiche for the application's dedicated block. This tool does not cut videos and does not publish by API.",
    inputSchema: {
      project: z.string().trim().min(1).max(120),
      timezone: z.string().trim().default("Europe/Paris"),
      notes: z.string().trim().max(3000).default(""),
      publications: z.array(legacyPublicationSchema).min(1).max(100),
    },
    annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false, idempotentHint: false },
  }, async ({ project, timezone, notes, publications }) => {
    const library = await loadLibrary();
    if (!library) throw new Error("Ouvre Cut Vidéo et attends Synchro ChatGPT OK.");

    const compatibilityRead = project === "__CUTVIDEO_LIBRARY__"
      || publications.some((publication) => publication.video_name === "__CUTVIDEO_LIBRARY__");
    if (compatibilityRead) {
      const projects = library.projects.map((item) => {
        const stats = projectStats(library, item);
        return {
          name: item.name,
          video_count: item.videos.length,
          pending_count: stats.pendingCount,
          videos: item.videos,
          pending_videos: stats.pendingVideos,
          existing_schedules: stats.schedules,
          recommended_defaults: recommendedDefaults(library, item),
        };
      });
      return {
        structuredContent: {
          mode: "library_read_compat",
          app_version: library.app_version,
          synced_at_millis: library.synced_at_millis,
          project_count: projects.length,
          total_videos: projects.reduce((sum, item) => sum + item.video_count, 0),
          projects,
        },
        content: [{ type: "text", text: projects.flatMap((item) => [
          \`\${item.name} — \${item.pending_count} a traiter\`,
          ...item.pending_videos.map((video, index) => \`  \${index + 1}. \${video.name}\`),
        ]).join("\n") }],
      };
    }

    if (project === "__CUTVIDEO_ANALYZE__") {
      const requestedVideo = publications[0]?.video_name ?? "";
      const target = normalizeName(requestedVideo);
      let foundProject: LibraryProject | undefined;
      let foundVideo: LibraryProject["videos"][number] | undefined;
      for (const candidateProject of library.projects) {
        const candidateVideo = candidateProject.videos.find((video) => normalizeName(video.name) === target);
        if (candidateVideo) {
          foundProject = candidateProject;
          foundVideo = candidateVideo;
          break;
        }
      }
      if (!foundProject || !foundVideo) {
        throw new Error(\`Video introuvable: \${requestedVideo}\`);
      }
      const images = await loadVideoFrameContents(foundVideo);
      return {
        structuredContent: {
          mode: "video_analysis_compat",
          project: foundProject.name,
          video: foundVideo,
          frame_count: images.length,
          analysis_available: images.length > 0 || Boolean(foundVideo.transcript.trim()),
        },
        content: [
          { type: "text", text: \`ANALYSE CUT VIDEO — \${foundProject.name} / \${foundVideo.name} — \${images.length} frame(s)\` },
          ...images,
        ],
      };
    }

    const selected = selectProject(library, project, publications.map((publication) => publication.video_name));
    const realProject = selected.project;
    const stats = projectStats(library, realProject);
    const realNames = new Set(realProject.videos.map((video) => normalizeName(video.name)));
    const invalid = publications.filter((publication) => !realNames.has(normalizeName(publication.video_name)));
    if (invalid.length) {
      return {
        structuredContent: {
          mode: "retry_with_real_files",
          project: realProject.name,
          invalid_video_names: invalid.map((item) => item.video_name),
          pending_videos: stats.pendingVideos,
          instruction: "Recommence immediatement avec les vrais noms pending_videos, sans demander a l'utilisateur.",
        },
        content: [{ type: "text", text: [
          "Noms obsoletes detectes. Utilise ces vrais fichiers:",
          ...stats.pendingVideos.map((video, index) => \`\${index + 1}. \${video.name}\`),
        ].join("\n") }],
      };
    }

    const ordered = [...publications].sort((a, b) => a.order - b.order).map(preparePublication);
    const pack = await savePack(realProject.name, ordered);
    const fiche = [
      \`FICHE PUBLICATION — \${realProject.name}\`,
      \`Fuseau: \${timezone}\`,
      \`Total: \${ordered.length}\`,
      "",
      ...ordered.flatMap((p) => [
        \`\${p.order}. \${p.video_name}\`,
        \`\${p.account_label} • \${p.platform.toUpperCase()}\`,
        \`\${p.date} a \${p.time} • \${statusLabel(p.status)}\`,
        p.metadata_text,
        "",
      ]),
      notes ? "NOTES" : "", notes,
      \`IMPORTER: \${pack.import_url}\`,
    ].filter((line, index, array) => !(line === "" && array[index - 1] === "")).join("\n").trim();
    return {
      structuredContent: {
        project: realProject.name,
        timezone,
        publications: ordered,
        fiche_bloc: fiche,
        batch_id: pack.id,
        import_all_url: pack.import_url,
      },
      content: [{ type: "text", text: fiche }],
    };
  });

  return server;
}
`;

if (!source.includes("function createStableServer(): McpServer")) {
  source = source.replace("\nfunction sendMcpError", `${stableServer}\nfunction sendMcpError`);
}

source = source.replace("const server = createServer();", "const server = createStableServer();");
source = source.replaceAll('version: "2.1.1"', 'version: "2.2.0"');
source = source.replaceAll("Cut Vidéo MCP v2.1.1 listening on port", "Cut Vidéo MCP v2.2.0 listening on port");
source = source.replace(
  'automatic_project_selection: true,\n  frame_analysis: true,',
  'automatic_project_selection: true,\n  connector_mode: "stable_3_tools",\n  connector_schema: "cutvideo-legacy-compatible-v1",\n  frame_analysis: true,',
);

await writeFile(path, source, "utf8");
console.log("CUTVIDEO_CONNECTOR_STABLE_PATCH applied: frozen-snapshot compatible 3-tool MCP");
