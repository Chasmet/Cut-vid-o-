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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.chasmet.cutvideo.databinding.ActivitySavedVideosBinding;
import com.chasmet.cutvideo.databinding.DialogFolderNoteBinding;
import com.chasmet.cutvideo.databinding.DialogLibraryDisplayBinding;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class SavedVideosActivity extends AppCompatActivity {

    private static final DateTimeFormatter NOTE_DATE_FORMAT = DateTimeFormatter.ofPattern(
            "dd/MM/yyyy 'à' HH:mm"
    );

    private ActivitySavedVideosBinding binding;
    private SavedFolderAdapter folderAdapter;
    private SavedFolderAdapter unassignedFolderAdapter;
    private VideoCollectionAdapter collectionAdapter;
    private ConcatAdapter rootAdapter;
    private SavedVideoAdapter videoAdapter;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private List<SavedVideoFolder> folders = new ArrayList<>();
    private List<VideoCollection> collections = new ArrayList<>();
    private LibraryDisplaySettings displaySettings;
    private String currentCollectionId;
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

        displaySettings = LibraryDisplayPreferences.get(this);
        configureSystemInsets();
        configureSystemDeleteLauncher();
        configureAdapters();
        configureActions();
        configureBackHandling();
    }

    private void configureAdapters() {
        folderAdapter = createFolderAdapter();
        unassignedFolderAdapter = createFolderAdapter();
        collectionAdapter = new VideoCollectionAdapter(
                this,
                displaySettings,
                new VideoCollectionAdapter.Actions() {
                    @Override
                    public void openCollection(VideoCollection collection) {
                        showCollection(collection);
                    }

                    @Override
                    public void editNote(VideoCollection collection) {
                        showCollectionNoteEditor(collection);
                    }

                    @Override
                    public void renameCollection(VideoCollection collection) {
                        showCollectionNameDialog(collection, null);
                    }

                    @Override
                    public void shareCollection(VideoCollection collection) {
                        shareVideos(collection.getAllVideos());
                    }

                    @Override
                    public void deleteCollection(VideoCollection collection) {
                        promptDeleteCollection(collection);
                    }
                }
        );
        rootAdapter = new ConcatAdapter(collectionAdapter, unassignedFolderAdapter);

        videoAdapter = new SavedVideoAdapter(this, displaySettings, new SavedVideoAdapter.Actions() {
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

    private SavedFolderAdapter createFolderAdapter() {
        return new SavedFolderAdapter(this, displaySettings, new SavedFolderAdapter.Actions() {
            @Override
            public void openFolder(SavedVideoFolder folder) {
                showFolder(folder);
            }

            @Override
            public void editNote(SavedVideoFolder folder) {
                showFolderNoteEditor(folder);
            }

            @Override
            public void renameFolder(SavedVideoFolder folder) {
                promptRenameFolder(folder);
            }

            @Override
            public void moveFolder(SavedVideoFolder folder) {
                promptMoveFolder(folder);
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
        binding.viewSettingsButton.setOnClickListener(view -> showDisplaySettingsDialog());
        binding.actionButton.setOnClickListener(view -> {
            if (currentFolderKey != null) {
                setSelectionMode(!selectionMode);
            } else if (currentCollectionId != null) {
                promptAddFolderToCurrentCollection();
            } else {
                showCollectionNameDialog(null, null);
            }
        });
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
                VideoCollectionRepository.reconcile(this, folders);
                collections = VideoCollectionRepository.list(this, folders);
                binding.progressBar.setVisibility(View.GONE);
                SavedVideoFolder currentFolder = findFolder(currentFolderKey);
                VideoCollection currentCollection = findCollection(currentCollectionId);
                if (currentFolderKey != null && currentFolder != null) {
                    showFolder(currentFolder);
                } else if (currentCollectionId != null && currentCollection != null) {
                    showCollection(currentCollection);
                } else {
                    showFolderList();
                }
            });
        });
    }

    private void showFolderList() {
        currentCollectionId = null;
        currentFolderKey = null;
        setSelectionMode(false);
        binding.folderNotePanel.setVisibility(View.GONE);
        binding.titleText.setText(R.string.saved_title);
        List<VideoCollection> displayedCollections = LibrarySorter.collections(
                collections,
                displaySettings.getSortMode()
        );
        List<SavedVideoFolder> unassignedFolders = LibrarySorter.folders(
                unassignedFolders(),
                displaySettings.getSortMode()
        );
        if (folders.isEmpty() && collections.isEmpty()) {
            binding.folderSummaryText.setVisibility(View.GONE);
        } else {
            binding.folderSummaryText.setText(getString(
                    R.string.library_summary_with_collections,
                    collections.size(),
                    folders.size(),
                    totalVideoCount(),
                    TimeFormatter.fileSize(totalVideoSizeBytes())
            ));
            binding.folderSummaryText.setVisibility(View.VISIBLE);
        }
        binding.actionButton.setText(R.string.create_collection_short);
        binding.actionButton.setVisibility(View.VISIBLE);
        binding.emptyText.setText(R.string.empty_saved);
        applyOrganizationLayout();
        binding.videosList.setAdapter(rootAdapter);
        collectionAdapter.submit(displayedCollections);
        unassignedFolderAdapter.submit(unassignedFolders);
        showContentState(displayedCollections.isEmpty() && unassignedFolders.isEmpty());
    }

    private void showCollection(VideoCollection collection) {
        currentCollectionId = collection.getId();
        currentFolderKey = null;
        setSelectionMode(false);
        showCollectionNotePanel(collection);
        binding.titleText.setText(collection.getName());
        binding.folderSummaryText.setText(getString(
                R.string.collection_summary,
                collection.getFolderCount(),
                collection.getVideoCount(),
                TimeFormatter.fileSize(collection.getTotalSizeBytes())
        ));
        binding.folderSummaryText.setVisibility(View.VISIBLE);
        binding.actionButton.setText(R.string.add_folder_short);
        binding.actionButton.setVisibility(View.VISIBLE);
        binding.emptyText.setText(R.string.empty_collection);
        applyOrganizationLayout();
        binding.videosList.setAdapter(folderAdapter);
        List<SavedVideoFolder> displayedFolders = LibrarySorter.folders(
                collection.getFolders(),
                displaySettings.getSortMode()
        );
        folderAdapter.submit(displayedFolders);
        showContentState(collection.getFolders().isEmpty());
    }

    private void showFolder(SavedVideoFolder folder) {
        currentFolderKey = folder.getKey();
        setSelectionMode(false);
        showFolderNotePanel(folder);
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
        applyVideoLayout();
        binding.videosList.setAdapter(videoAdapter);
        videoAdapter.submit(LibrarySorter.videos(
                folder.getVideos(),
                displaySettings.getSortMode()
        ));
        showContentState(folder.getVideos().isEmpty());
    }

    private void showContentState(boolean empty) {
        binding.emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.videosList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void applyOrganizationLayout() {
        if (displaySettings.usesGrid()) {
            binding.videosList.setLayoutManager(new GridLayoutManager(
                    this,
                    displaySettings.gridSpanCount()
            ));
        } else {
            binding.videosList.setLayoutManager(new LinearLayoutManager(this));
        }
        int horizontalPadding = dp(displaySettings.usesGrid() ? 9 : 14);
        binding.videosList.setPadding(
                horizontalPadding,
                dp(displaySettings.usesGrid() ? 9 : 14),
                horizontalPadding,
                dp(24)
        );
    }

    private void applyVideoLayout() {
        if (displaySettings.usesGrid()) {
            binding.videosList.setLayoutManager(new GridLayoutManager(
                    this,
                    displaySettings.gridSpanCount()
            ));
        } else {
            binding.videosList.setLayoutManager(new LinearLayoutManager(this));
        }
        int horizontalPadding = dp(displaySettings.usesGrid()
                ? 9
                : displaySettings.getItemSize() == LibraryDisplaySettings.SIZE_SMALL ? 8 : 14);
        binding.videosList.setPadding(
                horizontalPadding,
                dp(displaySettings.usesGrid() ? 9 : 12),
                horizontalPadding,
                dp(24)
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showCollectionNotePanel(VideoCollection collection) {
        FolderNote note = FolderNoteRepository.getCollection(this, collection.getId());
        showNotePanel(
                note,
                R.string.collection_notes_label,
                () -> showCollectionNoteEditor(collection)
        );
    }

    private void showFolderNotePanel(SavedVideoFolder folder) {
        FolderNote note = FolderNoteRepository.getFolder(this, folder.getKey());
        showNotePanel(note, R.string.folder_notes_label, () -> showFolderNoteEditor(folder));
    }

    private void showNotePanel(FolderNote note, int labelResource, Runnable editorAction) {
        binding.folderNotePanel.setVisibility(View.VISIBLE);
        binding.notePanelLabelText.setText(labelResource);
        if (note.isEmpty()) {
            binding.notePanelPreviewText.setText(R.string.folder_note_empty);
            binding.notePanelUpdatedText.setText(R.string.folder_note_tap_to_write);
        } else {
            binding.notePanelPreviewText.setText(note.getText());
            binding.notePanelUpdatedText.setText(getString(
                    R.string.folder_note_updated,
                    formatNoteDate(note.getUpdatedAtMillis())
            ));
        }
        binding.folderNotePanel.setOnClickListener(view -> editorAction.run());
        binding.editNoteButton.setOnClickListener(view -> editorAction.run());
    }

    private void showCollectionNoteEditor(VideoCollection collection) {
        showNoteEditor(true, collection.getId(), collection.getName());
    }

    private void showFolderNoteEditor(SavedVideoFolder folder) {
        showNoteEditor(false, folder.getKey(), folderTitle(folder));
    }

    private void showNoteEditor(boolean collection, String key, String displayName) {
        FolderNote note = collection
                ? FolderNoteRepository.getCollection(this, key)
                : FolderNoteRepository.getFolder(this, key);
        DialogFolderNoteBinding editor = DialogFolderNoteBinding.inflate(getLayoutInflater());
        editor.noteScopeText.setText(getString(
                collection ? R.string.note_for_collection : R.string.note_for_folder,
                displayName
        ));
        editor.noteInput.setText(note.getText());
        editor.noteInput.setSelection(editor.noteInput.length());
        updateNoteCharacterCount(editor);
        editor.noteInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                updateNoteCharacterCount(editor);
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.folder_notepad)
                .setView(editor.getRoot())
                .setNeutralButton(R.string.insert_date_time, null)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save_note, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                String stamp = "[" + NOTE_DATE_FORMAT.format(
                        Instant.now().atZone(ZoneId.systemDefault())
                ) + "] ";
                int cursor = Math.max(0, editor.noteInput.getSelectionStart());
                editor.noteInput.getText().insert(cursor, stamp);
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String text = editor.noteInput.getText().toString();
                if (collection) {
                    FolderNoteRepository.saveCollection(this, key, text);
                } else {
                    FolderNoteRepository.saveFolder(this, key, text);
                }
                dialog.dismiss();
                Toast.makeText(
                        this,
                        FolderNoteRepository.normalizeText(text).isEmpty()
                                ? R.string.folder_note_cleared
                                : R.string.folder_note_saved,
                        Toast.LENGTH_SHORT
                ).show();
                refreshOrganizationView();
            });
        });
        dialog.show();
    }

    private void updateNoteCharacterCount(DialogFolderNoteBinding editor) {
        editor.noteCharacterCountText.setText(getString(
                R.string.note_character_count,
                editor.noteInput.length(),
                FolderNoteRepository.MAX_NOTE_LENGTH
        ));
    }

    private String formatNoteDate(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "";
        }
        return NOTE_DATE_FORMAT.format(
                Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
        );
    }

    private void showDisplaySettingsDialog() {
        DialogLibraryDisplayBinding editor = DialogLibraryDisplayBinding.inflate(
                getLayoutInflater()
        );
        editor.layoutGroup.check(displaySettings.usesGrid()
                ? R.id.gridLayoutRadio
                : R.id.listLayoutRadio);
        if (displaySettings.getItemSize() == LibraryDisplaySettings.SIZE_SMALL) {
            editor.sizeGroup.check(R.id.smallSizeRadio);
        } else if (displaySettings.getItemSize() == LibraryDisplaySettings.SIZE_LARGE) {
            editor.sizeGroup.check(R.id.largeSizeRadio);
        } else {
            editor.sizeGroup.check(R.id.normalSizeRadio);
        }
        if (displaySettings.getSortMode() == LibraryDisplaySettings.SORT_NAME) {
            editor.sortGroup.check(R.id.nameSortRadio);
        } else if (displaySettings.getSortMode() == LibraryDisplaySettings.SORT_SIZE) {
            editor.sortGroup.check(R.id.sizeSortRadio);
        } else if (displaySettings.getSortMode() == LibraryDisplaySettings.SORT_COUNT) {
            editor.sortGroup.check(R.id.countSortRadio);
        } else {
            editor.sortGroup.check(R.id.recentSortRadio);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.display_settings)
                .setView(editor.getRoot())
                .setNeutralButton(
                        R.string.reset_display,
                        (ignored, which) -> applyDisplaySettings(
                                LibraryDisplaySettings.defaults()
                        )
                )
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apply, (ignored, which) -> {
                    int displayMode = editor.layoutGroup.getCheckedRadioButtonId()
                            == R.id.gridLayoutRadio
                            ? LibraryDisplaySettings.MODE_GRID
                            : LibraryDisplaySettings.MODE_LIST;
                    int itemSize;
                    if (editor.sizeGroup.getCheckedRadioButtonId() == R.id.smallSizeRadio) {
                        itemSize = LibraryDisplaySettings.SIZE_SMALL;
                    } else if (editor.sizeGroup.getCheckedRadioButtonId()
                            == R.id.largeSizeRadio) {
                        itemSize = LibraryDisplaySettings.SIZE_LARGE;
                    } else {
                        itemSize = LibraryDisplaySettings.SIZE_NORMAL;
                    }
                    int sortMode;
                    int checkedSort = editor.sortGroup.getCheckedRadioButtonId();
                    if (checkedSort == R.id.nameSortRadio) {
                        sortMode = LibraryDisplaySettings.SORT_NAME;
                    } else if (checkedSort == R.id.sizeSortRadio) {
                        sortMode = LibraryDisplaySettings.SORT_SIZE;
                    } else if (checkedSort == R.id.countSortRadio) {
                        sortMode = LibraryDisplaySettings.SORT_COUNT;
                    } else {
                        sortMode = LibraryDisplaySettings.SORT_RECENT;
                    }
                    applyDisplaySettings(new LibraryDisplaySettings(
                            displayMode,
                            itemSize,
                            sortMode
                    ));
                })
                .show();
    }

    private void applyDisplaySettings(LibraryDisplaySettings settings) {
        displaySettings = settings;
        LibraryDisplayPreferences.save(this, settings);
        folderAdapter.setSettings(settings);
        unassignedFolderAdapter.setSettings(settings);
        collectionAdapter.setSettings(settings);
        videoAdapter.setSettings(settings);
        refreshOrganizationView();
        Toast.makeText(this, R.string.display_settings_saved, Toast.LENGTH_SHORT).show();
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

    private VideoCollection findCollection(String id) {
        if (id == null) {
            return null;
        }
        for (VideoCollection collection : collections) {
            if (id.equals(collection.getId())) {
                return collection;
            }
        }
        return null;
    }

    private List<SavedVideoFolder> unassignedFolders() {
        Set<String> assignedKeys = new HashSet<>();
        for (VideoCollection collection : collections) {
            for (SavedVideoFolder folder : collection.getFolders()) {
                assignedKeys.add(folder.getKey());
            }
        }
        List<SavedVideoFolder> result = new ArrayList<>();
        for (SavedVideoFolder folder : folders) {
            if (!assignedKeys.contains(folder.getKey())) {
                result.add(folder);
            }
        }
        return result;
    }

    private void setSelectionMode(boolean enabled) {
        selectionMode = enabled && currentFolderKey != null;
        if (videoAdapter != null) {
            videoAdapter.setSelectionMode(selectionMode);
        }
        binding.selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        binding.viewSettingsButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        binding.folderNotePanel.setVisibility(
                selectionMode || (currentCollectionId == null && currentFolderKey == null)
                        ? View.GONE
                        : View.VISIBLE
        );
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
        String oldFolderKey = folder.getKey();
        showRenameDialog(
                R.string.rename_folder,
                folderTitle(folder),
                newName -> {
                    String newFolderKey = VideoFolderUtils.renamedFolderKey(
                            oldFolderKey,
                            newName
                    );
                    runStorageOperation(
                            () -> {
                                boolean renamed = MediaStoreRepository.renameFolder(
                                        this,
                                        folder,
                                        newFolderKey
                                );
                                if (renamed) {
                                    VideoCollectionRepository.updateFolderKey(
                                            this,
                                            oldFolderKey,
                                            newFolderKey
                                    );
                                    FolderNoteRepository.updateFolderKey(
                                            this,
                                            oldFolderKey,
                                            newFolderKey
                                    );
                                    if (oldFolderKey.equals(currentFolderKey)) {
                                        currentFolderKey = newFolderKey;
                                    }
                                }
                                return renamed;
                            },
                            R.string.folder_renamed
                    );
                }
        );
    }

    private void showCollectionNameDialog(
            VideoCollection collection,
            String folderKeyToAssign
    ) {
        boolean creating = collection == null;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(R.string.collection_name_hint);
        input.setText(creating ? "" : collection.getName());
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
                .setTitle(creating ? R.string.create_collection : R.string.rename_collection)
                .setMessage(creating ? getString(R.string.collection_create_explanation) : null)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(creating ? R.string.create : R.string.rename, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            input.selectAll();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String name = VideoFolderUtils.normalizeUserName(input.getText().toString());
                if (name.isEmpty()) {
                    input.setError(getString(R.string.invalid_new_name));
                    return;
                }

                if (creating) {
                    String collectionId = VideoCollectionRepository.create(this, name);
                    if (collectionId == null) {
                        input.setError(getString(R.string.collection_name_exists));
                        return;
                    }
                    if (folderKeyToAssign != null) {
                        VideoCollectionRepository.assignFolder(
                                this,
                                folderKeyToAssign,
                                collectionId
                        );
                    }
                    Toast.makeText(this, R.string.collection_created, Toast.LENGTH_SHORT).show();
                } else if (!VideoCollectionRepository.rename(
                        this,
                        collection.getId(),
                        name
                )) {
                    input.setError(getString(R.string.collection_name_exists));
                    return;
                } else {
                    Toast.makeText(this, R.string.collection_renamed, Toast.LENGTH_SHORT).show();
                }

                dialog.dismiss();
                refreshOrganizationView();
            });
        });
        dialog.show();
    }

    private void promptMoveFolder(SavedVideoFolder folder) {
        if (collections.isEmpty()) {
            showCollectionNameDialog(null, folder.getKey());
            return;
        }

        CharSequence[] destinations = new CharSequence[collections.size() + 2];
        destinations[0] = getString(R.string.root_collection);
        int selectedIndex = 0;
        String assignedCollectionId = VideoCollectionRepository.collectionIdForFolder(
                this,
                folder.getKey()
        );
        for (int index = 0; index < collections.size(); index++) {
            VideoCollection collection = collections.get(index);
            destinations[index + 1] = collection.getName();
            if (collection.getId().equals(assignedCollectionId)) {
                selectedIndex = index + 1;
            }
        }
        destinations[destinations.length - 1] = getString(R.string.create_new_collection);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.move_folder_title, folderTitle(folder)))
                .setSingleChoiceItems(destinations, selectedIndex, null)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.move, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int destinationIndex = dialog.getListView().getCheckedItemPosition();
                    if (destinationIndex == destinations.length - 1) {
                        dialog.dismiss();
                        showCollectionNameDialog(null, folder.getKey());
                        return;
                    }
                    String destinationId = destinationIndex <= 0
                            ? null
                            : collections.get(destinationIndex - 1).getId();
                    if (!VideoCollectionRepository.assignFolder(
                            this,
                            folder.getKey(),
                            destinationId
                    )) {
                        Toast.makeText(this, R.string.folder_move_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    Toast.makeText(this, R.string.folder_moved, Toast.LENGTH_SHORT).show();
                    refreshOrganizationView();
                })
        );
        dialog.show();
    }

    private void promptAddFolderToCurrentCollection() {
        VideoCollection targetCollection = findCollection(currentCollectionId);
        if (targetCollection == null) {
            showFolderList();
            return;
        }

        List<SavedVideoFolder> candidates = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        for (SavedVideoFolder folder : folders) {
            String assignedId = VideoCollectionRepository.collectionIdForFolder(
                    this,
                    folder.getKey()
            );
            if (targetCollection.getId().equals(assignedId)) {
                continue;
            }
            candidates.add(folder);
            VideoCollection assignedCollection = findCollection(assignedId);
            labels.add(assignedCollection == null
                    ? folderTitle(folder)
                    : getString(
                            R.string.folder_in_collection,
                            folderTitle(folder),
                            assignedCollection.getName()
                    ));
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.no_folder_to_add, Toast.LENGTH_LONG).show();
            return;
        }

        boolean[] selected = new boolean[candidates.size()];
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.add_folders_to_collection)
                .setMultiChoiceItems(
                        labels.toArray(new CharSequence[0]),
                        selected,
                        (ignored, which, checked) -> selected[which] = checked
                )
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int movedCount = 0;
                    for (int index = 0; index < candidates.size(); index++) {
                        if (selected[index] && VideoCollectionRepository.assignFolder(
                                this,
                                candidates.get(index).getKey(),
                                targetCollection.getId()
                        )) {
                            movedCount++;
                        }
                    }
                    if (movedCount == 0) {
                        Toast.makeText(
                                this,
                                R.string.select_at_least_one_folder,
                                Toast.LENGTH_SHORT
                        ).show();
                        return;
                    }
                    dialog.dismiss();
                    Toast.makeText(
                            this,
                            getResources().getQuantityString(
                                    R.plurals.folders_added,
                                    movedCount,
                                    movedCount
                            ),
                            Toast.LENGTH_SHORT
                    ).show();
                    refreshOrganizationView();
                })
        );
        dialog.show();
    }

    private void promptDeleteCollection(VideoCollection collection) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.delete_collection)
                .setMessage(getString(
                        R.string.delete_collection_message,
                        collection.getName(),
                        collection.getFolderCount()
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (ignored, which) -> {
                    if (!VideoCollectionRepository.delete(this, collection.getId())) {
                        Toast.makeText(
                                this,
                                R.string.collection_delete_failed,
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    FolderNoteRepository.deleteCollection(this, collection.getId());
                    if (collection.getId().equals(currentCollectionId)) {
                        currentCollectionId = null;
                        currentFolderKey = null;
                    }
                    Toast.makeText(this, R.string.collection_deleted, Toast.LENGTH_SHORT).show();
                    refreshOrganizationView();
                })
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.danger))
        );
        dialog.show();
    }

    private void refreshOrganizationView() {
        VideoCollectionRepository.reconcile(this, folders);
        collections = VideoCollectionRepository.list(this, folders);
        SavedVideoFolder currentFolder = findFolder(currentFolderKey);
        VideoCollection currentCollection = findCollection(currentCollectionId);
        if (currentFolder != null) {
            showFolder(currentFolder);
        } else if (currentCollection != null) {
            showCollection(currentCollection);
        } else {
            showFolderList();
        }
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
            VideoCollection parentCollection = findCollection(currentCollectionId);
            if (parentCollection != null) {
                showCollection(parentCollection);
            } else {
                showFolderList();
            }
        } else if (currentCollectionId != null) {
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
