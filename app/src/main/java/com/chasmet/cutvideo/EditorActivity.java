package com.chasmet.cutvideo;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.chasmet.cutvideo.databinding.ActivityEditorBinding;

import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public final class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI = "video_uri";

    private static final int[] QUICK_DURATIONS_SECONDS = {15, 30, 60, 90};

    private ActivityEditorBinding binding;
    private Uri videoUri;
    private String videoName;
    private ExoPlayer player;
    private long videoDurationMs;
    private long trimStartMs;
    private long trimEndMs;
    private int timelineMaxSeconds = 1;
    private boolean durationReady;
    private boolean syncingCustomDuration;
    private boolean exporting;
    private Mode selectedMode = Mode.QUICK;
    private VideoExportManager exportManager;
    private AlertDialog exportDialog;
    private TextView exportProgressText;

    private enum Mode {
        QUICK,
        CUSTOM,
        TRIM
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String uriValue = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        if (uriValue == null || uriValue.trim().isEmpty()) {
            showFatalVideoError();
            return;
        }
        videoUri = Uri.parse(uriValue);
        videoName = VideoUtils.displayName(this, videoUri);
        binding.videoNameText.setText(videoName);

        configureToolbar();
        configureModeButtons();
        configureQuickPanel();
        configureCustomPanel();
        configureTrimPanel();
        configureBackHandling();
        setExportEnabled(false);
        setupPlayer();

        long metadataDuration = VideoUtils.readDuration(this, videoUri);
        if (metadataDuration > 0) {
            initializeDuration(metadataDuration);
        }
    }

    private void configureToolbar() {
        binding.backButton.setOnClickListener(view -> handleBack());
        binding.topSaveButton.setOnClickListener(view -> startExport());
        binding.exportButton.setOnClickListener(view -> startExport());
    }

    private void configureModeButtons() {
        binding.quickModeButton.setOnClickListener(view -> selectMode(Mode.QUICK));
        binding.customModeButton.setOnClickListener(view -> selectMode(Mode.CUSTOM));
        binding.trimModeButton.setOnClickListener(view -> selectMode(Mode.TRIM));
        selectMode(Mode.QUICK);
    }

    private void configureQuickPanel() {
        String[] choices = {
                "15 secondes",
                "30 secondes",
                "60 secondes",
                "90 secondes"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                choices
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.durationSpinner.setAdapter(adapter);
        binding.durationSpinner.setSelection(3);
        binding.durationSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(
                position -> updateQuickSummary()
        ));
    }

    private void configureCustomPanel() {
        binding.customDurationSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || syncingCustomDuration) {
                    return;
                }
                syncingCustomDuration = true;
                binding.customDurationInput.setText(String.valueOf(progress));
                binding.customDurationInput.setSelection(
                        binding.customDurationInput.getText().length()
                );
                syncingCustomDuration = false;
                updateCustomSummary();
            }
        });

        binding.customDurationInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (syncingCustomDuration) {
                    return;
                }
                Integer seconds = readCustomSeconds();
                if (seconds != null) {
                    int bounded = Math.max(
                            binding.customDurationSeekBar.getMin(),
                            Math.min(binding.customDurationSeekBar.getMax(), seconds)
                    );
                    syncingCustomDuration = true;
                    binding.customDurationSeekBar.setProgress(bounded);
                    syncingCustomDuration = false;
                }
                updateCustomSummary();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
    }

    private void configureTrimPanel() {
        binding.startSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!durationReady) {
                    return;
                }
                int endProgress = binding.endSeekBar.getProgress();
                if (progress >= endProgress) {
                    int corrected = Math.max(0, endProgress - 1);
                    if (progress != corrected) {
                        seekBar.setProgress(corrected);
                        return;
                    }
                }
                trimStartMs = positionForProgress(seekBar.getProgress());
                if (fromUser && player != null) {
                    player.seekTo(trimStartMs);
                }
                updateTrimSummary();
            }
        });

        binding.endSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!durationReady) {
                    return;
                }
                int startProgress = binding.startSeekBar.getProgress();
                if (progress <= startProgress) {
                    int corrected = Math.min(seekBar.getMax(), startProgress + 1);
                    if (progress != corrected) {
                        seekBar.setProgress(corrected);
                        return;
                    }
                }
                trimEndMs = positionForProgress(seekBar.getProgress());
                if (fromUser && player != null) {
                    player.seekTo(Math.max(0, trimEndMs - 250));
                }
                updateTrimSummary();
            }
        });
    }

    private void configureBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this).build();
        binding.playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(videoUri));
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && !durationReady) {
                    long playerDuration = player.getDuration();
                    if (playerDuration != C.TIME_UNSET && playerDuration > 0) {
                        initializeDuration(playerDuration);
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (!durationReady) {
                    showFatalVideoError();
                } else {
                    Toast.makeText(
                            EditorActivity.this,
                            R.string.invalid_video,
                            Toast.LENGTH_LONG
                    ).show();
                }
            }
        });
        player.prepare();
    }

    private void initializeDuration(long durationMs) {
        if (durationReady || durationMs <= 0) {
            return;
        }
        durationReady = true;
        videoDurationMs = durationMs;
        trimStartMs = 0L;
        trimEndMs = durationMs;
        timelineMaxSeconds = (int) Math.max(
                1L,
                Math.min(Integer.MAX_VALUE, (durationMs + 999L) / 1_000L)
        );

        binding.videoDurationText.setText("Durée totale : " + TimeFormatter.duration(durationMs));
        binding.startSeekBar.setMax(timelineMaxSeconds);
        binding.startSeekBar.setProgress(0);
        binding.endSeekBar.setMax(timelineMaxSeconds);
        binding.endSeekBar.setProgress(timelineMaxSeconds);

        int customMinimum = timelineMaxSeconds >= 5 ? 5 : 1;
        int customMaximum = Math.max(customMinimum, Math.min(600, timelineMaxSeconds));
        int customDefault = Math.min(30, customMaximum);
        binding.customDurationSeekBar.setMin(customMinimum);
        binding.customDurationSeekBar.setMax(customMaximum);
        binding.customDurationSeekBar.setProgress(customDefault);
        syncingCustomDuration = true;
        binding.customDurationInput.setText(String.valueOf(customDefault));
        syncingCustomDuration = false;

        updateQuickSummary();
        updateCustomSummary();
        updateTrimSummary();
        setExportEnabled(true);
    }

    private void selectMode(Mode mode) {
        selectedMode = mode;
        binding.quickPanel.setVisibility(mode == Mode.QUICK ? View.VISIBLE : View.GONE);
        binding.customPanel.setVisibility(mode == Mode.CUSTOM ? View.VISIBLE : View.GONE);
        binding.trimPanel.setVisibility(mode == Mode.TRIM ? View.VISIBLE : View.GONE);
        styleModeButton(binding.quickModeButton, mode == Mode.QUICK);
        styleModeButton(binding.customModeButton, mode == Mode.CUSTOM);
        styleModeButton(binding.trimModeButton, mode == Mode.TRIM);
    }

    private void styleModeButton(Button button, boolean selected) {
        button.setBackgroundResource(
                selected ? R.drawable.bg_mode_selected : R.drawable.bg_mode_unselected
        );
        button.setTextColor(getColor(selected ? R.color.background : R.color.white));
    }

    private void updateQuickSummary() {
        if (!durationReady) {
            return;
        }
        int seconds = selectedQuickSeconds();
        int count = SegmentPlanner.count(videoDurationMs, seconds * 1_000L);
        binding.quickInfoText.setText(getString(R.string.segments_info, count, seconds));
    }

    private void updateCustomSummary() {
        if (!durationReady) {
            return;
        }
        Integer seconds = readCustomSeconds();
        if (seconds == null || seconds <= 0) {
            binding.customInfoText.setText(R.string.invalid_duration);
            binding.customInfoText.setTextColor(getColor(R.color.danger));
            return;
        }
        int count = SegmentPlanner.count(videoDurationMs, seconds * 1_000L);
        binding.customInfoText.setText(getString(R.string.segments_info, count, seconds));
        binding.customInfoText.setTextColor(getColor(R.color.text_secondary));
    }

    private void updateTrimSummary() {
        if (!durationReady) {
            return;
        }
        binding.startLabel.setText(getString(R.string.start) + " : "
                + TimeFormatter.duration(trimStartMs));
        binding.endLabel.setText(getString(R.string.end) + " : "
                + TimeFormatter.duration(trimEndMs));
        binding.trimInfoText.setText(getString(
                R.string.trim_info,
                TimeFormatter.duration(trimStartMs),
                TimeFormatter.duration(trimEndMs),
                TimeFormatter.duration(trimEndMs - trimStartMs)
        ));
    }

    private int selectedQuickSeconds() {
        int position = binding.durationSpinner.getSelectedItemPosition();
        if (position < 0 || position >= QUICK_DURATIONS_SECONDS.length) {
            return 90;
        }
        return QUICK_DURATIONS_SECONDS[position];
    }

    private Integer readCustomSeconds() {
        String rawValue = binding.customDurationInput.getText().toString().trim();
        if (rawValue.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(rawValue);
            return value > 0 ? value : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private long positionForProgress(int progress) {
        if (progress >= timelineMaxSeconds) {
            return videoDurationMs;
        }
        return Math.min(videoDurationMs, progress * 1_000L);
    }

    private List<SegmentPlanner.Segment> createExportPlan() {
        if (selectedMode == Mode.TRIM) {
            return SegmentPlanner.trim(videoDurationMs, trimStartMs, trimEndMs);
        }
        int seconds;
        if (selectedMode == Mode.QUICK) {
            seconds = selectedQuickSeconds();
        } else {
            Integer customSeconds = readCustomSeconds();
            if (customSeconds == null) {
                throw new IllegalArgumentException(getString(R.string.invalid_duration));
            }
            seconds = customSeconds;
        }
        return SegmentPlanner.split(videoDurationMs, seconds * 1_000L);
    }

    private void startExport() {
        if (!durationReady || exporting) {
            return;
        }

        List<SegmentPlanner.Segment> plan;
        try {
            plan = createExportPlan();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        exporting = true;
        setExportEnabled(false);
        if (player != null) {
            player.pause();
        }
        showExportDialog();

        exportManager = new VideoExportManager(
                this,
                videoUri,
                plan,
                VideoUtils.outputBaseName(videoName),
                new VideoExportManager.Listener() {
                    @Override
                    public void onSegmentStarted(int current, int total) {
                        if (exportProgressText != null) {
                            exportProgressText.setText(getString(
                                    R.string.export_progress,
                                    current,
                                    total
                            ));
                        }
                    }

                    @Override
                    public void onCompleted(List<Uri> savedUris) {
                        exporting = false;
                        exportManager = null;
                        dismissExportDialog();
                        setExportEnabled(true);
                        showCompletedDialog(savedUris.size());
                    }

                    @Override
                    public void onError(String message) {
                        exporting = false;
                        exportManager = null;
                        dismissExportDialog();
                        setExportEnabled(true);
                        if (!isFinishing() && !isDestroyed()) {
                            new AlertDialog.Builder(EditorActivity.this)
                                    .setTitle(R.string.error_title)
                                    .setMessage(getString(R.string.export_error, message))
                                    .setPositiveButton(R.string.close, null)
                                    .show();
                        }
                    }
                }
        );
        exportManager.start();
    }

    private void showExportDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding / 2);

        ProgressBar progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.getIndeterminateDrawable().setTint(getColor(R.color.teal));
        content.addView(progressBar, new LinearLayout.LayoutParams(padding * 2, padding * 2));

        exportProgressText = new TextView(this);
        exportProgressText.setTextColor(Color.WHITE);
        exportProgressText.setTextSize(16f);
        exportProgressText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.topMargin = padding / 2;
        content.addView(exportProgressText, textParams);

        exportDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.exporting)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setCancelable(false)
                .create();
        exportDialog.setOnShowListener(dialog -> exportDialog
                .getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(view -> cancelExport()));
        exportDialog.show();
    }

    private void cancelExport() {
        if (exportManager != null) {
            exportManager.cancel();
            exportManager = null;
        }
        exporting = false;
        dismissExportDialog();
        setExportEnabled(durationReady);
    }

    private void dismissExportDialog() {
        if (exportDialog != null) {
            exportDialog.dismiss();
            exportDialog = null;
        }
        exportProgressText = null;
    }

    private void showCompletedDialog(int count) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.done_title)
                .setMessage(getString(R.string.done_message, count))
                .setPositiveButton(R.string.view_videos, (dialog, which) -> startActivity(
                        new Intent(this, SavedVideosActivity.class)
                ))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void setExportEnabled(boolean enabled) {
        binding.exportButton.setEnabled(enabled);
        binding.exportButton.setAlpha(enabled ? 1f : 0.45f);
        binding.topSaveButton.setEnabled(enabled);
        binding.topSaveButton.setAlpha(enabled ? 1f : 0.45f);
        binding.quickModeButton.setEnabled(enabled);
        binding.customModeButton.setEnabled(enabled);
        binding.trimModeButton.setEnabled(enabled);
    }

    private void handleBack() {
        if (exporting) {
            Toast.makeText(this, R.string.exporting, Toast.LENGTH_SHORT).show();
            return;
        }
        finish();
    }

    private void showFatalVideoError() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(R.string.invalid_video)
                .setCancelable(false)
                .setPositiveButton(R.string.close, (dialog, which) -> finish())
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (exportManager != null) {
            exportManager.cancel();
            exportManager = null;
        }
        dismissExportDialog();
        if (player != null) {
            player.release();
            player = null;
        }
        binding.playerView.setPlayer(null);
        super.onDestroy();
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
