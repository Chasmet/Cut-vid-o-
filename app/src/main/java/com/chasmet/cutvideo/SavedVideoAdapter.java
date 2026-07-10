package com.chasmet.cutvideo;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chasmet.cutvideo.databinding.ItemSavedVideoBinding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SavedVideoAdapter
        extends RecyclerView.Adapter<SavedVideoAdapter.VideoViewHolder> {

    public interface Actions {
        void open(SavedVideo video);

        void share(SavedVideo video);
    }

    private final Context context;
    private final Actions actions;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<SavedVideo> videos = new ArrayList<>();

    public SavedVideoAdapter(Context context, Actions actions) {
        this.context = context.getApplicationContext();
        this.actions = actions;
    }

    public void submit(List<SavedVideo> newVideos) {
        videos.clear();
        videos.addAll(newVideos);
        notifyDataSetChanged();
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
        holder.binding.nameText.setText(video.getName());
        holder.binding.detailsText.setText(context.getString(
                R.string.file_details,
                TimeFormatter.duration(video.getDurationMs()),
                TimeFormatter.fileSize(video.getSizeBytes())
        ));
        holder.binding.thumbnail.setTag(uriTag);
        holder.binding.thumbnail.setImageResource(R.drawable.ic_video);
        holder.binding.openButton.setOnClickListener(view -> actions.open(video));
        holder.binding.shareButton.setOnClickListener(view -> actions.share(video));
        holder.binding.getRoot().setOnClickListener(view -> actions.open(video));
        loadThumbnail(holder, video, uriTag);
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

