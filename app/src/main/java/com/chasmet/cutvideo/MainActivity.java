package com.chasmet.cutvideo;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.chasmet.cutvideo.databinding.ActivityMainBinding;

public final class MainActivity extends AppCompatActivity {

    private static final long REMOTE_POLL_INTERVAL_MS = 15_000L;
    private static final String SYNC_PREFS = "cut_video_chatgpt_sync";
    private static final String DEVICE_TOKEN_KEY = "device_token";

    private ActivityMainBinding binding;
    private ActivityResultLauncher<PickVisualMediaRequest> videoPicker;
    private ActivityResultLauncher<String> mediaPermissionLauncher;
    private boolean mediaPermissionRequested;
    private final Handler remotePollHandler = new Handler(Looper.getMainLooper());
    private final Runnable remotePoll = new Runnable() {
        @Override
        public void run() {
            pullRemoteCommands();
            remotePollHandler.postDelayed(this, REMOTE_POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        PublicationReminderReceiver.ensureNotificationChannel(this);
        PublicationReminderScheduler.rescheduleAll(this);

        videoPicker = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                this::handleSelectedVideo
        );
        mediaPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        startLibrarySyncAndRemotePolling();
                    } else {
                        Toast.makeText(
                                this,
                                "Autorise l'accès aux vidéos pour récupérer les anciens fichiers Cut Vidéo.",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );

        binding.importVideoCard.setOnClickListener(view -> openVideoPicker());
        binding.savedVideosCard.setOnClickListener(view -> startActivity(new Intent(this, SavedVideosActivity.class)));
        binding.chatgptAssistantCard.setOnClickListener(view -> startActivity(new Intent(this, ChatGptAssistantActivity.class)));
        binding.settingsCard.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();

        AppUpdateManager.resumePendingUpdate(this);
        AppUpdateManager.consumeMcpUpdateRequest(this);
        AppUpdateManager.checkAutomatically(this);

        CutVideoCommandListenerService.stopListening(this);

        if (!hasMediaReadPermission()) {
            remotePollHandler.removeCallbacks(remotePoll);
            if (!mediaPermissionRequested) {
                mediaPermissionRequested = true;
                mediaPermissionLauncher.launch(requiredMediaPermission());
            }
            return;
        }

        startLibrarySyncAndRemotePolling();
    }

    @Override
    protected void onPause() {
        remotePollHandler.removeCallbacks(remotePoll);
        CutVideoCommandListenerService.startListening(this);
        super.onPause();
    }

    private boolean hasMediaReadPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                requiredMediaPermission()
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private String requiredMediaPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return Manifest.permission.READ_MEDIA_VIDEO;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void startLibrarySyncAndRemotePolling() {
        CutVideoLibrarySync.syncAsync(this);
        remotePollHandler.removeCallbacks(remotePoll);
        remotePollHandler.postDelayed(remotePoll, 4_000L);
    }

    private void pullRemoteCommands() {
        SharedPreferences preferences = getSharedPreferences(SYNC_PREFS, MODE_PRIVATE);
        String token = preferences.getString(DEVICE_TOKEN_KEY, "");
        if (token == null || token.trim().isEmpty()) return;
        String safeToken = token.trim();
        CutVideoRemoteScheduleSync.pullAsync(this, safeToken);
        CutVideoRemoteAdminSync.pullAsync(this, safeToken);
    }

    private void openVideoPicker() {
        PickVisualMediaRequest request = new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                .build();
        videoPicker.launch(request);
    }

    private void handleSelectedVideo(Uri uri) {
        if (uri == null) {
            Toast.makeText(this, R.string.picker_cancelled, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        Intent editor = new Intent(this, EditorActivity.class);
        editor.putExtra(EditorActivity.EXTRA_VIDEO_URI, uri.toString());
        editor.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(editor);
    }
}
