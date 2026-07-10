package com.chasmet.cutvideo;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.chasmet.cutvideo.databinding.ActivitySavedVideosBinding;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class SavedVideosActivity extends AppCompatActivity {

    private ActivitySavedVideosBinding binding;
    private SavedVideoAdapter adapter;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGeneration = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySavedVideosBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.backButton.setOnClickListener(view -> finish());
        adapter = new SavedVideoAdapter(this, new SavedVideoAdapter.Actions() {
            @Override
            public void open(SavedVideo video) {
                openVideo(video);
            }

            @Override
            public void share(SavedVideo video) {
                shareVideo(video);
            }
        });
        binding.videosList.setLayoutManager(new LinearLayoutManager(this));
        binding.videosList.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadVideos();
    }

    private void loadVideos() {
        int generation = loadGeneration.incrementAndGet();
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.emptyText.setVisibility(View.GONE);
        binding.videosList.setVisibility(View.GONE);

        loader.execute(() -> {
            List<SavedVideo> videos = MediaStoreRepository.loadSavedVideos(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != loadGeneration.get()) {
                    return;
                }
                binding.progressBar.setVisibility(View.GONE);
                adapter.submit(videos);
                if (videos.isEmpty()) {
                    binding.emptyText.setVisibility(View.VISIBLE);
                    binding.videosList.setVisibility(View.GONE);
                } else {
                    binding.emptyText.setVisibility(View.GONE);
                    binding.videosList.setVisibility(View.VISIBLE);
                }
            });
        });
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

    private void shareVideo(SavedVideo video) {
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, video.getUri());
        intent.setClipData(ClipData.newUri(
                getContentResolver(),
                video.getName(),
                video.getUri()
        ));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_video)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.invalid_video, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        loadGeneration.incrementAndGet();
        loader.shutdownNow();
        if (adapter != null) {
            adapter.close();
        }
        super.onDestroy();
    }
}
