package com.chasmet.cutvideo;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exporte les morceaux l'un après l'autre afin de limiter la mémoire et la chauffe du téléphone. */
@OptIn(markerClass = UnstableApi.class)
public final class VideoExportManager {

    public interface Listener {
        void onSegmentStarted(int current, int total);

        void onCompleted(List<Uri> savedUris);

        void onError(String message);
    }

    private final Context context;
    private final Uri sourceUri;
    private final List<SegmentPlanner.Segment> segments;
    private final String baseName;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Uri> savedUris = new ArrayList<>();

    private int currentIndex;
    private Transformer currentTransformer;
    private File currentTemporaryFile;

    public VideoExportManager(
            Context context,
            Uri sourceUri,
            List<SegmentPlanner.Segment> segments,
            String baseName,
            Listener listener
    ) {
        this.context = context.getApplicationContext();
        this.sourceUri = sourceUri;
        this.segments = new ArrayList<>(segments);
        this.baseName = baseName;
        this.listener = listener;
    }

    public void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("L'export doit démarrer sur le fil principal.");
        }
        if (segments.isEmpty()) {
            listener.onError("Aucun morceau à créer.");
            return;
        }
        exportNext();
    }

    public void cancel() {
        cancelled.set(true);
        if (currentTransformer != null) {
            currentTransformer.cancel();
            currentTransformer = null;
        }
        deleteTemporaryFile();
        fileExecutor.shutdownNow();
    }

    private void exportNext() {
        if (cancelled.get()) {
            return;
        }
        if (currentIndex >= segments.size()) {
            fileExecutor.shutdown();
            listener.onCompleted(new ArrayList<>(savedUris));
            return;
        }

        listener.onSegmentStarted(currentIndex + 1, segments.size());
        SegmentPlanner.Segment segment = segments.get(currentIndex);

        try {
            File baseDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (baseDirectory == null) {
                baseDirectory = context.getFilesDir();
            }
            File temporaryDirectory = new File(baseDirectory, "exports-temporaires");
            if (!temporaryDirectory.exists() && !temporaryDirectory.mkdirs()) {
                throw new IOException("Le dossier temporaire ne peut pas être créé.");
            }
            currentTemporaryFile = new File(
                    temporaryDirectory,
                    "cut-" + UUID.randomUUID() + ".mp4"
            );

            MediaItem.ClippingConfiguration clipping =
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(segment.getStartMs())
                            .setEndPositionMs(segment.getEndMs())
                            .build();
            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(sourceUri)
                    .setClippingConfiguration(clipping)
                    .build();
            EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

            currentTransformer = new Transformer.Builder(context)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult exportResult) {
                            currentTransformer = null;
                            publishCurrentFile();
                        }

                        @Override
                        public void onError(
                                Composition composition,
                                ExportResult exportResult,
                                ExportException exportException
                        ) {
                            currentTransformer = null;
                            fail(exportException.getLocalizedMessage());
                        }
                    })
                    .build();
            currentTransformer.start(editedMediaItem, currentTemporaryFile.getAbsolutePath());
        } catch (IOException | RuntimeException error) {
            fail(error.getLocalizedMessage());
        }
    }

    private void publishCurrentFile() {
        File completedFile = currentTemporaryFile;
        currentTemporaryFile = null;
        int exportedIndex = currentIndex;
        fileExecutor.execute(() -> {
            if (cancelled.get()) {
                if (completedFile != null) {
                    completedFile.delete();
                }
                return;
            }
            try {
                String outputName = outputName(exportedIndex);
                Uri savedUri = MediaStoreRepository.publishMp4(
                        context,
                        completedFile,
                        outputName,
                        baseName
                );
                completedFile.delete();
                mainHandler.post(() -> {
                    if (cancelled.get()) {
                        return;
                    }
                    savedUris.add(savedUri);
                    currentIndex++;
                    exportNext();
                });
            } catch (IOException error) {
                completedFile.delete();
                mainHandler.post(() -> fail(error.getLocalizedMessage()));
            }
        });
    }

    private String outputName(int index) {
        if (segments.size() == 1) {
            return baseName + ".mp4";
        }
        return String.format(Locale.FRANCE, "%s_%02d.mp4", baseName, index + 1);
    }

    private void fail(String rawMessage) {
        if (cancelled.getAndSet(true)) {
            return;
        }
        if (currentTransformer != null) {
            currentTransformer.cancel();
            currentTransformer = null;
        }
        deleteTemporaryFile();
        fileExecutor.shutdownNow();
        String message = rawMessage == null || rawMessage.trim().isEmpty()
                ? "Erreur vidéo inconnue."
                : rawMessage;
        listener.onError(message);
    }

    private void deleteTemporaryFile() {
        if (currentTemporaryFile != null) {
            currentTemporaryFile.delete();
            currentTemporaryFile = null;
        }
    }
}
