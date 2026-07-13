package com.chasmet.cutvideo;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.chasmet.cutvideo.databinding.ActivitySavedVideosBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class SavedVideosActivity extends AppCompatActivity {

    private ActivitySavedVideosBinding binding;
    private SavedFolderAdapter folderAdapter;
    private SavedVideoAdapter videoAdapter;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private List<SavedVideoFolder> folders = new ArrayList<>();
    private String currentFolderKey;
    private boolean selectionMode;
    private boolean storageOperationRunning;
    private ActivityResultLauncher<IntentSenderRequest> systemDeleteLauncher;
    private List<SavedVideo> pendingSystemDeleteVideos = new ArrayList<>();
    private int pendingDirectDeleteCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySavedVideosBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        configureSystemInsets();
        configureSystemDeleteLauncher();
        binding.videosList.setLayoutManager(new LinearLayoutManager(this));
        configureAdapters();
        configureActions();
        configureBackHandling();
    }

    private void configureAdapters() {
        folderAdapter = new SavedFolderAdapter(this, new SavedFolderAdapter.Actions() {
            @Override
            public void openFolder(SavedVideoFolder folder) {
                showFolder(folder);
            }

            @Override
            public void renameFolder(SavedVideoFolder folder) {
                promptRenameFolder(folder);
            }

            @Override
            public void shareFolder(SavedVideoFolder folder) {
                shareVideos(folder.getVideos());
            }

            @Override
            public void deleteFolder(SavedVideoFolder folder) {
                promptDeleteFolder(folder);
            }
        });

        videoAdapter = new SavedVideoAdapter(this, new SavedVideoAdapter.Actions() {
            @Override
            public void open(SavedVideo video) {
                openVideo(video);
            }

            @Override
            public void rename(SavedVideo video) {
                promptRenameVideo(video);
            }

            @Override
            public void share(SavedVideo video) {
                shareVideos(Collections.singletonList(video));
            }

            @Override
            public void delete(SavedVideo video) {
                promptDeleteVideo(video);
            }

            @Override
            public void schedule(SavedVideo video) {
                startActivity(VideoScheduleActivity.createIntent(
                        SavedVideosActivity.this,
                        video
                ));
            }

            @Override
            public void startSelection(SavedVideo video) {
                setSelectionMode(true);
                videoAdapter.selectVideo(video);
            }

            @Override
            public void onSelectionChanged(int selectedCount, int totalCount) {
                updateSelectionUi(selectedCount, totalCount);
            }
        });
    }

    private void configureSystemDeleteLauncher() {
        systemDeleteLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    boolean confirmed = result.getResultCode() == RESULT_OK;
                    int failedCount = confirmed ? 0 : pendingSystemDeleteVideos.size();
                    int deletedCount = pendingDirectDeleteCount;
                    if (confirmed) {
                        clearLocalVideoData(pendingSystemDeleteVideos);
                        deletedCount += pendingSystemDeleteVideos.size();
                    }
                    pendingSystemDeleteVideos = new ArrayList<>();
                    pendingDirectDeleteCount = 0;
                    finishDeleteOperation(deletedCount, failedCount, !confirmed);
                }
        );
    }

    private void configureSystemInsets() {
        if (Build.VERSION.SDK_INT < 35) {
            return;
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());
    }

    private void configureActions() {
        binding.backButton.setOnClickListener(view -> handleBack());
        binding.actionButton.setOnClickListener(view -> setSelectionMode(!selectionMode));
        binding.selectAllButton.setOnClickListener(view -> videoAdapter.toggleSelectAll());
        binding.shareSelectedButton.setOnClickListener(view -> shareSelectedVideos());
        binding.deleteSelectedButton.setOnClickListener(view -> promptDeleteSelection());
    }

    private void configureBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!storageOperationRunning) {
            loadFolders();
        }
    }

    private void loadFolders() {
        int generation = loadGeneration.incrementAndGet();
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.emptyText.setVisibility(View.GONE);
        binding.videosList.setVisibility(View.GONE);

        loader.execute(() -> {
            List<SavedVideoFolder> loadedFolders = MediaStoreRepository.loadSavedVideoFolders(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != loadGeneration.get()) {
                    return;
                }
                folders = loadedFolders;
                binding.progressBar.setVisibility(View.GONE);
                SavedVideoFolder currentFolder = findFolder(currentFolderKey);
                if (currentFolderKey != null && currentFolder != null) {
                    showFolder(currentFolder);
                } else {
                    showFolderList();
                }
            });
        });
    }

    private void showFolderList() {
        currentFolderKey = null;
        setSelectionMode(false);
        binding.titleText.setText(R.string.saved_title);
        if (folders.isEmpty()) {
            binding.folderSummaryText.setVisibility(View.GONE);
        } else {
            binding.folderSummaryText.setText(getString(
                    R.string.library_summary,
                    folders.size(),
                    totalVideoCount(),
                    TimeFormatter.fileSize(totalVideoSizeBytes())
            ));
            binding.folderSummaryText.setVisibility(View.VISIBLE);
        }
        binding.actionButton.setVisibility(View.GONE);
        binding.emptyText.setText(R.string.empty_saved);
        binding.videosList.setAdapter(folderAdapter);
        folderAdapter.submit(folders);
        showContentState(folders.isEmpty());
    }

    private void showFolder(SavedVideoFolder folder) {
        currentFolderKey = folder.getKey();
        setSelectionMode(false);
        binding.titleText.setText(folderTitle(folder));
        binding.folderSummaryText.setText(getString(
                R.string.folder_summary,
                folder.getVideoCount(),
                TimeFormatter.fileSize(folder.getTotalSizeBytes())
        ));
        binding.folderSummaryText.setVisibility(View.VISIBLE);
        binding.actionButton.setText(R.string.select_files);
        binding.actionButton.setVisibility(View.VISIBLE);
        binding.emptyText.setText(R.string.empty_folder);
        binding.videosList.setAdapter(videoAdapter);
        videoAdapter.submit(folder.getVideos());
        showContentState(folder.getVideos().isEmpty());
    }

    private void showContentState(boolean empty) {
        binding.emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.videosList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private String folderTitle(SavedVideoFolder folder) {
        return VideoFolderUtils.isLegacy(folder.getKey())
                ? getString(R.string.legacy_folder)
                : VideoFolderUtils.displayName(folder.getKey());
    }

    private SavedVideoFolder findFolder(String key) {
        if (key == null) {
            return null;
        }
        for (SavedVideoFolder folder : folders) {
            if (key.equals(folder.getKey())) {
                return folder;
            }
        }
        return null;
    }

    private void setSelectionMode(boolean enabled) {
        selectionMode = enabled && currentFolderKey != null;
        if (videoAdapter != null) {
            videoAdapter.setSelectionMode(selectionMode);
        }
        binding.selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        binding.actionButton.setText(selectionMode ? R.string.cancel : R.string.select_files);
    }

    private void updateSelectionUi(int selectedCount, int totalCount) {
        if (!selectionMode) {
            return;
        }
        binding.shareSelectedButton.setEnabled(selectedCount > 0);
        binding.shareSelectedButton.setAlpha(selectedCount > 0 ? 1f : 0.45f);
        binding.deleteSelectedButton.setEnabled(selectedCount > 0);
        binding.deleteSelectedButton.setAlpha(selectedCount > 0 ? 1f : 0.45f);
        binding.selectionCountText.setText(getString(
                R.string.selection_summary,
                selectedCount,
                totalCount,
                TimeFormatter.fileSize(totalSizeBytes(videoAdapter.getSelectedVideos()))
        ));
        binding.selectAllButton.setText(
                totalCount > 0 && selectedCount == totalCount
                        ? R.string.deselect_all
                        : R.string.select_all
        );
    }

    private void shareSelectedVideos() {
        List<SavedVideo> selectedVideos = videoAdapter.getSelectedVideos();
        if (selectedVideos.isEmpty()) {
            Toast.makeText(this, R.string.select_at_least_one, Toast.LENGTH_SHORT).show();
            return;
        }
        shareVideos(selectedVideos);
        setSelectionMode(false);
    }

    private int totalVideoCount() {
        int count = 0;
        for (SavedVideoFolder folder : folders) {
            count += folder.getVideoCount();
        }
        return count;
    }

    private long totalVideoSizeBytes() {
        long size = 0L;
        for (SavedVideoFolder folder : folders) {
            size += folder.getTotalSizeBytes();
        }
        return size;
    }

    private long totalSizeBytes(List<SavedVideo> videos) {
        long size = 0L;
        for (SavedVideo video : videos) {
            size += Math.max(0L, video.getSizeBytes());
        }
        return size;
    }

    private void openVideo(SavedVideo video) {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(video.getUri(), "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.invalid_video, Toast.LENGTH_LONG).show();
        }
    }

    private void promptRenameFolder(SavedVideoFolder folder) {
        showRenameDialog(
                R.string.rename_folder,
                folderTitle(folder),
                newName -> runStorageOperation(
                        () -> MediaStoreRepository.renameFolder(
                                this,
                                folder,
                                VideoFolderUtils.renamedFolderKey(folder.getKey(), newName)
                        ),
                        R.string.folder_renamed
                )
        );
    }

    private void promptRenameVideo(SavedVideo video) {
        showRenameDialog(
                R.string.rename_video,
                VideoFolderUtils.editableVideoName(video.getName()),
                newName -> runStorageOperation(
                        () -> {
                            boolean renamed = MediaStoreRepository.renameVideo(this, video, newName);
                            if (renamed) {
                                PublicationScheduleRepository.updateVideoName(
                                        this,
                                        video.getUri().toString(),
                                        VideoFolderUtils.safeMp4DisplayName(newName)
                                );
                            }
                            return renamed;
                        },
                        R.string.video_renamed
                )
        );
    }

    private void promptDeleteFolder(SavedVideoFolder folder) {
        showDeleteConfirmation(
                getString(R.string.delete_folder_title),
                getString(
                        R.string.delete_folder_message,
                        folderTitle(folder),
                        folder.getVideoCount(),
                        TimeFormatter.fileSize(folder.getTotalSizeBytes())
                ),
                folder.getVideos()
        );
    }

    private void promptDeleteVideo(SavedVideo video) {
        showDeleteConfirmation(
                getString(R.string.delete_video_title),
                getString(R.string.delete_video_message, video.getName()),
                Collections.singletonList(video)
        );
    }

    private void promptDeleteSelection() {
        List<SavedVideo> selectedVideos = videoAdapter.getSelectedVideos();
        if (selectedVideos.isEmpty()) {
            Toast.makeText(this, R.string.select_at_least_one, Toast.LENGTH_SHORT).show();
            return;
        }
        showDeleteConfirmation(
                getString(R.string.delete_selection_title),
                getString(
                        R.string.delete_selection_message,
                        selectedVideos.size(),
                        TimeFormatter.fileSize(totalSizeBytes(selectedVideos))
                ),
                selectedVideos
        );
    }

    private void showDeleteConfirmation(
            String title,
            String message,
            List<SavedVideo> videos
    ) {
        List<SavedVideo> videosToDelete = new ArrayList<>(videos);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.delete,
                        (ignored, which) -> runDeleteOperation(videosToDelete)
                )
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.danger))
        );
        dialog.show();
    }

    private void showRenameDialog(
            int titleResource,
            String initialName,
            Consumer<String> onConfirmed
    ) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(R.string.new_name_hint);
        input.setText(initialName);
        input.setTextColor(getColor(R.color.white));
        input.setHintTextColor(getColor(R.color.text_secondary));
        input.setSelectAllOnFocus(true);

        int horizontalPadding = Math.round(24 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleResource)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.rename, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            input.selectAll();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String normalizedName = VideoFolderUtils.normalizeUserName(
                        input.getText().toString()
                );
                if (normalizedName.isEmpty()) {
                    input.setError(getString(R.string.invalid_new_name));
                    return;
                }
                dialog.dismiss();
                onConfirmed.accept(normalizedName);
            });
        });
        dialog.show();
    }

    private void runStorageOperation(StorageOperation operation, int successMessage) {
        if (storageOperationRunning) {
            return;
        }
        storageOperationRunning = true;
        int generation = loadGeneration.incrementAndGet();
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.videosList.setAlpha(0.55f);
        binding.actionButton.setEnabled(false);

        loader.execute(() -> {
            boolean success = operation.run();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != loadGeneration.get()) {
                    return;
                }
                storageOperationRunning = false;
                binding.videosList.setAlpha(1f);
                binding.actionButton.setEnabled(true);
                if (success) {
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show();
                    loadFolders();
                } else {
                    binding.progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void runDeleteOperation(List<SavedVideo> videos) {
        if (storageOperationRunning || videos.isEmpty()) {
            return;
        }
        storageOperationRunning = true;
        int generation = loadGeneration.incrementAndGet();
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.videosList.setAlpha(0.55f);
        binding.actionButton.setEnabled(false);

        loader.execute(() -> {
            MediaStoreRepository.DeleteResult result = MediaStoreRepository.deleteVideos(
                    this,
                    videos
            );
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != loadGeneration.get()) {
                    return;
                }
                clearLocalVideoData(result.getDeletedVideos());
                if (result.getFailedCount() > 0
                        && requestSystemDelete(
                                result.getFailedVideos(),
                                result.getDeletedCount()
                        )) {
                    return;
                }
                finishDeleteOperation(
                        result.getDeletedCount(),
                        result.getFailedCount(),
                        false
                );
            });
        });
    }

    private boolean requestSystemDelete(List<SavedVideo> failedVideos, int directDeletedCount) {
        if (Build.VERSION.SDK_INT < 30 || failedVideos.isEmpty()) {
            return false;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (SavedVideo video : failedVideos) {
            uris.add(video.getUri());
        }
        try {
            PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(), uris);
            pendingSystemDeleteVideos = new ArrayList<>(failedVideos);
            pendingDirectDeleteCount = directDeletedCount;
            systemDeleteLauncher.launch(
                    new IntentSenderRequest.Builder(request.getIntentSender()).build()
            );
            return true;
        } catch (RuntimeException error) {
            pendingSystemDeleteVideos = new ArrayList<>();
            pendingDirectDeleteCount = 0;
            return false;
        }
    }

    private void finishDeleteOperation(int deletedCount, int failedCount, boolean cancelled) {
        storageOperationRunning = false;
        binding.videosList.setAlpha(1f);
        binding.actionButton.setEnabled(true);

        if (deletedCount > 0) {
            String message = failedCount > 0
                    ? getString(R.string.delete_partial_result, deletedCount, failedCount)
                    : getString(R.string.delete_success, deletedCount);
            Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_LONG
            ).show();
            loadFolders();
        } else {
            binding.progressBar.setVisibility(View.GONE);
            Toast.makeText(
                    this,
                    cancelled ? R.string.delete_cancelled : R.string.delete_failed,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void clearLocalVideoData(List<SavedVideo> deletedVideos) {
        if (deletedVideos.isEmpty()) {
            return;
        }
        videoAdapter.clearShareTracking(deletedVideos);
        List<PublicationSchedule> removedSchedules =
                PublicationScheduleRepository.deleteForVideos(this, deletedVideos);
        PublicationReminderScheduler.cancelAll(this, removedSchedules);
    }

    private void shareVideos(List<SavedVideo> videos) {
        if (videos.isEmpty()) {
            return;
        }

        ArrayList<Uri> uris = new ArrayList<>();
        for (SavedVideo video : videos) {
            uris.add(video.getUri());
        }

        Intent intent;
        if (uris.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("video/mp4")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }

        ClipData clipData = ClipData.newUri(
                getContentResolver(),
                getString(R.string.share_video),
                uris.get(0)
        );
        for (int index = 1; index < uris.size(); index++) {
            clipData.addItem(new ClipData.Item(uris.get(index)));
        }
        intent.setClipData(clipData);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(intent, getString(
                    uris.size() == 1 ? R.string.share_video : R.string.share_multiple_videos
            )));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.share_not_supported, Toast.LENGTH_LONG).show();
        }
    }

    private void handleBack() {
        if (storageOperationRunning) {
            Toast.makeText(this, R.string.operation_in_progress, Toast.LENGTH_SHORT).show();
        } else if (selectionMode) {
            setSelectionMode(false);
        } else if (currentFolderKey != null) {
            showFolderList();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        loadGeneration.incrementAndGet();
        loader.shutdownNow();
        if (videoAdapter != null) {
            videoAdapter.close();
        }
        super.onDestroy();
    }

    private interface StorageOperation {
        boolean run();
    }
}
