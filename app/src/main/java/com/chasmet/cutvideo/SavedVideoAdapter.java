package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chasmet.cutvideo.databinding.ItemSavedVideoBinding;
import com.chasmet.cutvideo.databinding.ItemSavedVideoGridBinding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SavedVideoAdapter
        extends RecyclerView.Adapter<SavedVideoAdapter.VideoViewHolder> {

    private static final String PREFS_NAME = "video_share_tracking";
    private static final String PLATFORM_YOUTUBE = "youtube";
    private static final String PLATFORM_TIKTOK = "tiktok";
    private static final String PLATFORM_INSTAGRAM = "instagram";
    private static final String PLATFORM_X = "x";

    public interface Actions {
        void open(SavedVideo video);

        void rename(SavedVideo video);

        void share(SavedVideo video);

        void delete(SavedVideo video);

        void schedule(SavedVideo video);

        void startSelection(SavedVideo video);

        void onSelectionChanged(int selectedCount, int totalCount);
    }

    private final Context context;
    private final Actions actions;
    private final SharedPreferences sharePreferences;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<SavedVideo> videos = new ArrayList<>();
    private final Set<String> selectedVideoKeys = new HashSet<>();
    private boolean selectionMode;
    private LibraryDisplaySettings settings;

    public SavedVideoAdapter(
            Context context,
            LibraryDisplaySettings settings,
            Actions actions
    ) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.actions = actions;
        this.sharePreferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void submit(List<SavedVideo> newVideos) {
        videos.clear();
        videos.addAll(newVideos);
        selectedVideoKeys.retainAll(videoKeys(newVideos));
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void setSettings(LibraryDisplaySettings settings) {
        this.settings = settings;
        notifyDataSetChanged();
    }

    public void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        selectedVideoKeys.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public List<SavedVideo> getSelectedVideos() {
        List<SavedVideo> selected = new ArrayList<>();
        for (SavedVideo video : videos) {
            if (selectedVideoKeys.contains(video.getUri().toString())) {
                selected.add(video);
            }
        }
        return selected;
    }

    public void toggleSelectAll() {
        if (selectedVideoKeys.size() == videos.size()) {
            selectedVideoKeys.clear();
        } else {
            selectedVideoKeys.clear();
            selectedVideoKeys.addAll(videoKeys(videos));
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void selectVideo(SavedVideo video) {
        if (selectionMode) {
            setVideoSelected(video, true);
        }
    }

    public void clearShareTracking(List<SavedVideo> deletedVideos) {
        SharedPreferences.Editor editor = sharePreferences.edit();
        for (SavedVideo video : deletedVideos) {
            String videoKey = video.getUri().toString();
            for (SocialPlatform platform : SocialPlatform.values()) {
                editor.remove(buildPreferenceKey(videoKey, platform.getKey()));
            }
        }
        editor.apply();
    }

    public static void markPlatformShared(
            Context context,
            String videoUri,
            String platformKey
    ) {
        if (videoUri == null || platformKey == null) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(buildPreferenceKey(videoUri, platformKey), true)
                .apply();
    }

    @Override
    public int getItemViewType(int position) {
        return settings.usesGrid()
                ? LibraryDisplaySettings.MODE_GRID
                : LibraryDisplaySettings.MODE_LIST;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == LibraryDisplaySettings.MODE_GRID) {
            return new VideoViewHolder(ItemSavedVideoGridBinding.inflate(
                    inflater,
                    parent,
                    false
            ));
        }
        return new VideoViewHolder(ItemSavedVideoBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        SavedVideo video = videos.get(position);
        String uriTag = video.getUri().toString();
        boolean selected = selectedVideoKeys.contains(uriTag);
        int upcomingScheduleCount = PublicationScheduleRepository.countUpcomingForVideo(
                context,
                uriTag
        );

        applyItemSize(holder);
        holder.nameText.setText(video.getName());
        holder.detailsText.setText(context.getString(
                R.string.file_details,
                TimeFormatter.duration(video.getDurationMs()),
                TimeFormatter.fileSize(video.getSizeBytes())
        ));
        holder.thumbnail.setTag(uriTag);
        holder.thumbnail.setImageResource(R.drawable.ic_video);
        holder.root.setBackgroundResource(
                selected ? R.drawable.bg_card_selected : R.drawable.bg_card
        );
        bindSelection(holder, video, uriTag, selected);

        if (holder.isGrid()) {
            bindGridCard(holder, video, uriTag, upcomingScheduleCount);
        } else {
            bindListCard(holder, video, uriTag, upcomingScheduleCount);
        }
        loadThumbnail(holder, video, uriTag);
    }

    private void bindSelection(
            VideoViewHolder holder,
            SavedVideo video,
            String uriTag,
            boolean selected
    ) {
        holder.selectionCheck.setOnCheckedChangeListener(null);
        holder.selectionCheck.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.selectionCheck.setChecked(selected);
        holder.selectionCheck.setOnCheckedChangeListener((button, isChecked) ->
                setVideoSelected(video, isChecked)
        );
        holder.clickTarget.setOnClickListener(view -> {
            if (selectionMode) {
                setVideoSelected(video, !selectedVideoKeys.contains(uriTag));
            } else {
                actions.open(video);
            }
        });
        holder.clickTarget.setOnLongClickListener(view -> {
            if (!selectionMode) {
                actions.startSelection(video);
            }
            return true;
        });
    }

    private void bindListCard(
            VideoViewHolder holder,
            SavedVideo video,
            String uriTag,
            int upcomingScheduleCount
    ) {
        ItemSavedVideoBinding binding = holder.listBinding;
        int normalActionVisibility = selectionMode ? View.GONE : View.VISIBLE;
        binding.openButton.setVisibility(normalActionVisibility);
        binding.renameButton.setVisibility(normalActionVisibility);
        binding.shareButton.setVisibility(normalActionVisibility);
        binding.deleteButton.setVisibility(normalActionVisibility);
        binding.scheduleButton.setVisibility(normalActionVisibility);
        binding.trackingDivider.setVisibility(normalActionVisibility);
        binding.shareTrackingTitle.setVisibility(normalActionVisibility);
        binding.shareTrackingRow.setVisibility(normalActionVisibility);

        binding.openButton.setOnClickListener(view -> actions.open(video));
        binding.renameButton.setOnClickListener(view -> actions.rename(video));
        binding.shareButton.setOnClickListener(view -> actions.share(video));
        binding.deleteButton.setOnClickListener(view -> actions.delete(video));
        binding.scheduleButton.setText(
                upcomingScheduleCount == 0
                        ? context.getString(R.string.plan_publication)
                        : context.getResources().getQuantityString(
                                R.plurals.schedule_upcoming_count,
                                upcomingScheduleCount,
                                upcomingScheduleCount
                        )
        );
        binding.scheduleButton.setOnClickListener(view -> actions.schedule(video));

        bindTrackingCheckBox(binding.youtubeCheck, uriTag, PLATFORM_YOUTUBE);
        bindTrackingCheckBox(binding.tiktokCheck, uriTag, PLATFORM_TIKTOK);
        bindTrackingCheckBox(binding.instagramCheck, uriTag, PLATFORM_INSTAGRAM);
        bindTrackingCheckBox(binding.xCheck, uriTag, PLATFORM_X);
        binding.youtubeTracker.setOnClickListener(view -> binding.youtubeCheck.toggle());
        binding.tiktokTracker.setOnClickListener(view -> binding.tiktokCheck.toggle());
        binding.instagramTracker.setOnClickListener(view -> binding.instagramCheck.toggle());
        binding.xTracker.setOnClickListener(view -> binding.xCheck.toggle());
    }

    private void bindGridCard(
            VideoViewHolder holder,
            SavedVideo video,
            String uriTag,
            int upcomingScheduleCount
    ) {
        ItemSavedVideoGridBinding binding = holder.gridBinding;
        int actionsVisibility = selectionMode ? View.GONE : View.VISIBLE;
        binding.gridMoreButton.setVisibility(actionsVisibility);
        binding.gridScheduleButton.setVisibility(actionsVisibility);
        binding.gridScheduleButton.setText(
                upcomingScheduleCount == 0
                        ? context.getString(R.string.plan_publication_short)
                        : context.getResources().getQuantityString(
                                R.plurals.schedule_grid_count,
                                upcomingScheduleCount,
                                upcomingScheduleCount
                        )
        );
        binding.gridScheduleButton.setOnClickListener(view -> actions.schedule(video));
        binding.gridMoreButton.setOnClickListener(view -> showGridActionsMenu(
                view,
                video,
                uriTag
        ));
    }

    private void showGridActionsMenu(View anchor, SavedVideo video, String uriTag) {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        menu.inflate(R.menu.menu_saved_video_grid_actions);
        setTrackingMenuState(menu, R.id.action_track_youtube, uriTag, PLATFORM_YOUTUBE);
        setTrackingMenuState(menu, R.id.action_track_tiktok, uriTag, PLATFORM_TIKTOK);
        setTrackingMenuState(menu, R.id.action_track_instagram, uriTag, PLATFORM_INSTAGRAM);
        setTrackingMenuState(menu, R.id.action_track_x, uriTag, PLATFORM_X);
        menu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_open_video) {
                actions.open(video);
            } else if (itemId == R.id.action_schedule_video) {
                actions.schedule(video);
            } else if (itemId == R.id.action_rename_video) {
                actions.rename(video);
            } else if (itemId == R.id.action_share_video) {
                actions.share(video);
            } else if (itemId == R.id.action_select_video) {
                actions.startSelection(video);
            } else if (itemId == R.id.action_delete_video) {
                actions.delete(video);
            } else if (itemId == R.id.action_track_youtube) {
                toggleTracking(item, uriTag, PLATFORM_YOUTUBE);
            } else if (itemId == R.id.action_track_tiktok) {
                toggleTracking(item, uriTag, PLATFORM_TIKTOK);
            } else if (itemId == R.id.action_track_instagram) {
                toggleTracking(item, uriTag, PLATFORM_INSTAGRAM);
            } else if (itemId == R.id.action_track_x) {
                toggleTracking(item, uriTag, PLATFORM_X);
            } else {
                return false;
            }
            return true;
        });
        menu.show();
    }

    private void setTrackingMenuState(
            PopupMenu menu,
            int menuItemId,
            String videoKey,
            String platform
    ) {
        menu.getMenu().findItem(menuItemId).setChecked(sharePreferences.getBoolean(
                buildPreferenceKey(videoKey, platform),
                false
        ));
    }

    private void toggleTracking(
            android.view.MenuItem item,
            String videoKey,
            String platform
    ) {
        boolean checked = !sharePreferences.getBoolean(
                buildPreferenceKey(videoKey, platform),
                false
        );
        sharePreferences.edit()
                .putBoolean(buildPreferenceKey(videoKey, platform), checked)
                .apply();
        item.setChecked(checked);
    }

    private void applyItemSize(VideoViewHolder holder) {
        if (holder.isGrid()) {
            applyGridItemSize(holder);
            return;
        }
        int rowHeightDp;
        int thumbnailDp;
        float nameSp;
        float detailsSp;
        if (settings.getItemSize() == LibraryDisplaySettings.SIZE_SMALL) {
            rowHeightDp = 88;
            thumbnailDp = 78;
            nameSp = 14f;
            detailsSp = 11f;
        } else if (settings.getItemSize() == LibraryDisplaySettings.SIZE_LARGE) {
            rowHeightDp = 122;
            thumbnailDp = 112;
            nameSp = 17f;
            detailsSp = 14f;
        } else {
            rowHeightDp = 104;
            thumbnailDp = 96;
            nameSp = 15f;
            detailsSp = 13f;
        }
        ViewGroup.LayoutParams rowParams = holder.listBinding.videoInfoRow.getLayoutParams();
        rowParams.height = dp(rowHeightDp);
        holder.listBinding.videoInfoRow.setLayoutParams(rowParams);
        ViewGroup.LayoutParams thumbnailParams = holder.thumbnail.getLayoutParams();
        thumbnailParams.width = dp(thumbnailDp);
        thumbnailParams.height = dp(thumbnailDp);
        holder.thumbnail.setLayoutParams(thumbnailParams);
        holder.nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp);
        holder.detailsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, detailsSp);
    }

    private void applyGridItemSize(VideoViewHolder holder) {
        int thumbnailHeightDp;
        int nameHeightDp;
        float nameSp;
        float detailsSp;
        float buttonSp;
        if (settings.getItemSize() == LibraryDisplaySettings.SIZE_SMALL) {
            thumbnailHeightDp = 92;
            nameHeightDp = 34;
            nameSp = 11f;
            detailsSp = 9f;
            buttonSp = 7f;
        } else if (settings.getItemSize() == LibraryDisplaySettings.SIZE_LARGE) {
            thumbnailHeightDp = 238;
            nameHeightDp = 52;
            nameSp = 18f;
            detailsSp = 14f;
            buttonSp = 11f;
        } else {
            thumbnailHeightDp = 152;
            nameHeightDp = 40;
            nameSp = 14f;
            detailsSp = 11f;
            buttonSp = 9f;
        }
        ViewGroup.LayoutParams frameParams = holder.gridBinding.thumbnailFrame.getLayoutParams();
        frameParams.height = dp(thumbnailHeightDp);
        holder.gridBinding.thumbnailFrame.setLayoutParams(frameParams);
        ViewGroup.LayoutParams nameParams = holder.nameText.getLayoutParams();
        nameParams.height = dp(nameHeightDp);
        holder.nameText.setLayoutParams(nameParams);
        holder.nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp);
        holder.detailsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, detailsSp);
        holder.gridBinding.gridScheduleButton.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                buttonSp
        );
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void setVideoSelected(SavedVideo video, boolean selected) {
        String key = video.getUri().toString();
        if (selected) {
            selectedVideoKeys.add(key);
        } else {
            selectedVideoKeys.remove(key);
        }
        int position = videos.indexOf(video);
        if (position >= 0) {
            notifyItemChanged(position);
        }
        notifySelectionChanged();
    }

    private Set<String> videoKeys(List<SavedVideo> source) {
        Set<String> keys = new HashSet<>();
        for (SavedVideo video : source) {
            keys.add(video.getUri().toString());
        }
        return keys;
    }

    private void notifySelectionChanged() {
        actions.onSelectionChanged(selectedVideoKeys.size(), videos.size());
    }

    private void bindTrackingCheckBox(CheckBox checkBox, String videoKey, String platform) {
        String preferenceKey = buildPreferenceKey(videoKey, platform);
        checkBox.setOnCheckedChangeListener(null);
        checkBox.setChecked(sharePreferences.getBoolean(preferenceKey, false));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharePreferences.edit().putBoolean(preferenceKey, isChecked).apply()
        );
    }

    private static String buildPreferenceKey(String videoKey, String platform) {
        return platform + "|" + videoKey;
    }

    private void loadThumbnail(VideoViewHolder holder, SavedVideo video, String expectedTag) {
        thumbnailExecutor.execute(() -> {
            try {
                Bitmap bitmap = context.getContentResolver().loadThumbnail(
                        video.getUri(),
                        new Size(480, 270),
                        null
                );
                mainHandler.post(() -> {
                    Object currentTag = holder.thumbnail.getTag();
                    if (expectedTag.equals(currentTag)) {
                        holder.thumbnail.setImageBitmap(bitmap);
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                // L’icône vidéo reste visible si Android ne fournit pas de miniature.
            }
        });
    }

    @Override
    public int getItemCount() {
        return videos.size();
    }

    @Override
    public void onViewRecycled(@NonNull VideoViewHolder holder) {
        holder.thumbnail.setTag(null);
        holder.thumbnail.setImageResource(R.drawable.ic_video);
        holder.selectionCheck.setOnCheckedChangeListener(null);
        if (holder.listBinding != null) {
            holder.listBinding.youtubeCheck.setOnCheckedChangeListener(null);
            holder.listBinding.tiktokCheck.setOnCheckedChangeListener(null);
            holder.listBinding.instagramCheck.setOnCheckedChangeListener(null);
            holder.listBinding.xCheck.setOnCheckedChangeListener(null);
        }
        super.onViewRecycled(holder);
    }

    public void close() {
        thumbnailExecutor.shutdownNow();
    }

    static final class VideoViewHolder extends RecyclerView.ViewHolder {
        private final View root;
        private final View clickTarget;
        private final ImageView thumbnail;
        private final TextView nameText;
        private final TextView detailsText;
        private final CheckBox selectionCheck;
        private final ItemSavedVideoBinding listBinding;
        private final ItemSavedVideoGridBinding gridBinding;

        VideoViewHolder(ItemSavedVideoBinding binding) {
            super(binding.getRoot());
            root = binding.getRoot();
            clickTarget = binding.videoInfoRow;
            thumbnail = binding.thumbnail;
            nameText = binding.nameText;
            detailsText = binding.detailsText;
            selectionCheck = binding.selectionCheck;
            listBinding = binding;
            gridBinding = null;
        }

        VideoViewHolder(ItemSavedVideoGridBinding binding) {
            super(binding.getRoot());
            root = binding.getRoot();
            clickTarget = binding.getRoot();
            thumbnail = binding.thumbnail;
            nameText = binding.nameText;
            detailsText = binding.detailsText;
            selectionCheck = binding.selectionCheck;
            listBinding = null;
            gridBinding = binding;
        }

        boolean isGrid() {
            return gridBinding != null;
        }
    }
}
