package com.chasmet.cutvideo;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.chasmet.cutvideo.databinding.ActivitySavedVideosBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class SavedVideosActivity extends AppCompatActivity {

    private ActivitySavedVideosBinding binding;
    private SavedFolderAdapter folderAdapter;
    private SavedVideoAdapter videoAdapter;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private List<SavedVideoFolder> folders = new ArrayList<>();
    private String currentFolderKey;
    private boolean selectionMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySavedVideosBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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
            public void shareFolder(SavedVideoFolder folder) {
                shareVideos(folder.getVideos());
            }
        });

        videoAdapter = new SavedVideoAdapter(this, new SavedVideoAdapter.Actions() {
            @Override
            public void open(SavedVideo video) {
                openVideo(video);
            }

            @Override
            public void share(SavedVideo video) {
                shareVideos(Collections.singletonList(video));
            }

            @Override
            public void onSelectionChanged(int selectedCount, int totalCount) {
                updateSelectionUi(selectedCount, totalCount);
            }
        });
    }

    private void configureActions() {
        binding.backButton.setOnClickListener(view -> handleBack());
        binding.actionButton.setOnClickListener(view -> setSelectionMode(!selectionMode));
        binding.selectAllButton.setOnClickListener(view -> videoAdapter.toggleSelectAll());
        binding.shareSelectedButton.setOnClickListener(view -> shareSelectedVideos());
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
        loadFolders();
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
        binding.folderSummaryText.setVisibility(View.GONE);
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
        binding.selectionCountText.setText(getString(
                R.string.selection_count,
                selectedCount,
                totalCount
        ));
        binding.shareSelectedButton.setEnabled(selectedCount > 0);
        binding.shareSelectedButton.setAlpha(selectedCount > 0 ? 1f : 0.45f);
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
        if (selectionMode) {
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
}
