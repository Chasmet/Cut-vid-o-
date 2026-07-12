package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chasmet.cutvideo.databinding.ItemSavedVideoBinding;

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

        void share(SavedVideo video);

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

    public SavedVideoAdapter(Context context, Actions actions) {
        this.context = context.getApplicationContext();
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

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSavedVideoBinding binding = ItemSavedVideoBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new VideoViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        SavedVideo video = videos.get(position);
        String uriTag = video.getUri().toString();
        boolean selected = selectedVideoKeys.contains(uriTag);

        holder.binding.nameText.setText(video.getName());
        holder.binding.detailsText.setText(context.getString(
                R.string.file_details,
                TimeFormatter.duration(video.getDurationMs()),
                TimeFormatter.fileSize(video.getSizeBytes())
        ));

        holder.binding.thumbnail.setTag(uriTag);
        holder.binding.thumbnail.setImageResource(R.drawable.ic_video);
        holder.binding.getRoot().setBackgroundResource(
                selected ? R.drawable.bg_card_selected : R.drawable.bg_card
        );

        holder.binding.selectionCheck.setOnCheckedChangeListener(null);
        holder.binding.selectionCheck.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.binding.selectionCheck.setChecked(selected);
        holder.binding.selectionCheck.setOnCheckedChangeListener((button, isChecked) ->
                setVideoSelected(video, isChecked)
        );

        int normalActionVisibility = selectionMode ? View.GONE : View.VISIBLE;
        holder.binding.openButton.setVisibility(normalActionVisibility);
        holder.binding.shareButton.setVisibility(normalActionVisibility);
        holder.binding.trackingDivider.setVisibility(normalActionVisibility);
        holder.binding.shareTrackingTitle.setVisibility(normalActionVisibility);
        holder.binding.shareTrackingRow.setVisibility(normalActionVisibility);

        holder.binding.openButton.setOnClickListener(view -> actions.open(video));
        holder.binding.shareButton.setOnClickListener(view -> actions.share(video));
        holder.binding.videoInfoRow.setOnClickListener(view -> {
            if (selectionMode) {
                setVideoSelected(video, !selectedVideoKeys.contains(uriTag));
            } else {
                actions.open(video);
            }
        });

        bindTrackingCheckBox(holder.binding.youtubeCheck, uriTag, PLATFORM_YOUTUBE);
        bindTrackingCheckBox(holder.binding.tiktokCheck, uriTag, PLATFORM_TIKTOK);
        bindTrackingCheckBox(holder.binding.instagramCheck, uriTag, PLATFORM_INSTAGRAM);
        bindTrackingCheckBox(holder.binding.xCheck, uriTag, PLATFORM_X);

        holder.binding.youtubeTracker.setOnClickListener(view -> holder.binding.youtubeCheck.toggle());
        holder.binding.tiktokTracker.setOnClickListener(view -> holder.binding.tiktokCheck.toggle());
        holder.binding.instagramTracker.setOnClickListener(view -> holder.binding.instagramCheck.toggle());
        holder.binding.xTracker.setOnClickListener(view -> holder.binding.xCheck.toggle());

        loadThumbnail(holder, video, uriTag);
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

    private String buildPreferenceKey(String videoKey, String platform) {
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
                    Object currentTag = holder.binding.thumbnail.getTag();
                    if (expectedTag.equals(currentTag)) {
                        holder.binding.thumbnail.setImageBitmap(bitmap);
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                // L'icône vidéo reste visible si Android ne fournit pas de miniature.
            }
        });
    }

    @Override
    public int getItemCount() {
        return videos.size();
    }

    @Override
    public void onViewRecycled(@NonNull VideoViewHolder holder) {
        holder.binding.thumbnail.setTag(null);
        holder.binding.thumbnail.setImageResource(R.drawable.ic_video);
        holder.binding.selectionCheck.setOnCheckedChangeListener(null);
        holder.binding.youtubeCheck.setOnCheckedChangeListener(null);
        holder.binding.tiktokCheck.setOnCheckedChangeListener(null);
        holder.binding.instagramCheck.setOnCheckedChangeListener(null);
        holder.binding.xCheck.setOnCheckedChangeListener(null);
        super.onViewRecycled(holder);
    }

    public void close() {
        thumbnailExecutor.shutdownNow();
    }

    static final class VideoViewHolder extends RecyclerView.ViewHolder {
        private final ItemSavedVideoBinding binding;

        VideoViewHolder(ItemSavedVideoBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
