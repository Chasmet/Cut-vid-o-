package com.chasmet.cutvideo;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chasmet.cutvideo.databinding.ActivityVideoScheduleBinding;
import com.chasmet.cutvideo.databinding.DialogScheduleEditorBinding;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

public final class VideoScheduleActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI = "video_uri";
    public static final String EXTRA_VIDEO_NAME = "video_name";
    public static final String EXTRA_FOCUS_SCHEDULE_ID = "focus_schedule_id";
    public static final String EXTRA_PUBLISH_NOW = "publish_now";

    private ActivityVideoScheduleBinding binding;
    private VideoScheduleAdapter adapter;
    private String videoUri;
    private String videoName;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    public static Intent createIntent(Context context, SavedVideo video) {
        return createIntent(context, video.getUri().toString(), video.getName());
    }

    public static Intent createIntent(Context context, String videoUri, String videoName) {
        return new Intent(context, VideoScheduleActivity.class)
                .putExtra(EXTRA_VIDEO_URI, videoUri)
                .putExtra(EXTRA_VIDEO_NAME, videoName);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVideoScheduleBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        videoUri = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        videoName = getIntent().getStringExtra(EXTRA_VIDEO_NAME);
        if (videoUri == null || videoUri.trim().isEmpty()) {
            Toast.makeText(this, R.string.invalid_video, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (videoName == null || videoName.trim().isEmpty()) {
            videoName = getString(R.string.video_fallback_name);
        }

        configureSystemInsets();
        configureNotificationPermission();
        configureList();
        configureActions();
        PublicationReminderReceiver.ensureNotificationChannel(this);
        binding.videoNameText.setText(videoName);
        refreshSchedules();
        handleLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String incomingVideoUri = intent.getStringExtra(EXTRA_VIDEO_URI);
        String incomingVideoName = intent.getStringExtra(EXTRA_VIDEO_NAME);
        if (incomingVideoUri != null && !incomingVideoUri.isEmpty()) {
            videoUri = incomingVideoUri;
        }
        if (incomingVideoName != null && !incomingVideoName.isEmpty()) {
            videoName = incomingVideoName;
            binding.videoNameText.setText(videoName);
        }
        refreshSchedules();
        handleLaunchIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) {
            updateNotificationWarning();
        }
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

    private void configureNotificationPermission() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    updateNotificationWarning();
                    Toast.makeText(
                            this,
                            granted
                                    ? R.string.notifications_enabled
                                    : R.string.notifications_permission_denied,
                            Toast.LENGTH_LONG
                    ).show();
                }
        );
    }

    private void configureList() {
        adapter = new VideoScheduleAdapter(this, new VideoScheduleAdapter.Actions() {
            @Override
            public void publish(PublicationSchedule schedule) {
                shareNow(schedule);
            }

            @Override
            public void edit(PublicationSchedule schedule) {
                showScheduleEditor(schedule, false);
            }

            @Override
            public void duplicate(PublicationSchedule schedule) {
                showScheduleEditor(schedule, true);
            }

            @Override
            public void copyMetadata(PublicationSchedule schedule) {
                copyMetadataToClipboard(schedule, true);
            }

            @Override
            public void setPublished(PublicationSchedule schedule, boolean published) {
                updatePublishedState(schedule, published);
            }

            @Override
            public void delete(PublicationSchedule schedule) {
                confirmDeleteSchedule(schedule);
            }
        });
        binding.schedulesList.setLayoutManager(new LinearLayoutManager(this));
        binding.schedulesList.setAdapter(adapter);
    }

    private void configureActions() {
        binding.backButton.setOnClickListener(view -> finish());
        binding.addScheduleButton.setOnClickListener(view -> showScheduleEditor(null, false));
        binding.notificationWarning.setOnClickListener(view -> openNotificationSettings());
        binding.exactAlarmWarning.setOnClickListener(view -> openExactAlarmSettings());
    }

    private void refreshSchedules() {
        List<PublicationSchedule> schedules = PublicationScheduleRepository.listForVideo(
                this,
                videoUri
        );
        adapter.submit(schedules);
        boolean empty = schedules.isEmpty();
        binding.emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.schedulesList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void handleLaunchIntent(Intent intent) {
        String scheduleId = intent.getStringExtra(EXTRA_FOCUS_SCHEDULE_ID);
        if (scheduleId == null || scheduleId.isEmpty()) {
            return;
        }
        boolean publishNow = intent.getBooleanExtra(EXTRA_PUBLISH_NOW, false);
        intent.removeExtra(EXTRA_PUBLISH_NOW);
        PublicationSchedule schedule = PublicationScheduleRepository.get(this, scheduleId);
        if (schedule == null || !videoUri.equals(schedule.getVideoUri())) {
            return;
        }
        if (publishNow) {
            binding.getRoot().post(() -> shareNow(schedule));
            return;
        }
        int position = adapter.positionOf(scheduleId);
        if (position != RecyclerView.NO_POSITION) {
            binding.schedulesList.post(() -> binding.schedulesList.smoothScrollToPosition(position));
        }
    }

    private void showScheduleEditor(PublicationSchedule source, boolean duplicate) {
        DialogScheduleEditorBinding editor = DialogScheduleEditorBinding.inflate(
                getLayoutInflater()
        );
        configurePlatformSpinner(editor);
        configureVisibilitySpinner(editor);

        Calendar selectedTime = Calendar.getInstance();
        selectedTime.add(Calendar.HOUR_OF_DAY, 1);
        selectedTime.set(Calendar.SECOND, 0);
        selectedTime.set(Calendar.MILLISECOND, 0);
        if (source != null) {
            selectedTime.setTimeInMillis(source.getScheduledAtMillis());
            if (duplicate && selectedTime.getTimeInMillis() <= System.currentTimeMillis()) {
                selectedTime.setTimeInMillis(System.currentTimeMillis());
                selectedTime.add(Calendar.HOUR_OF_DAY, 1);
                selectedTime.set(Calendar.SECOND, 0);
                selectedTime.set(Calendar.MILLISECOND, 0);
            }
            editor.platformSpinner.setSelection(source.getPlatform().ordinal());
            editor.visibilitySpinner.setSelection(visibilityPosition(source.getVisibility()));
            editor.titleInput.setText(source.getTitle());
            editor.descriptionInput.setText(source.getDescription());
            editor.hashtagsInput.setText(source.getHashtags());
        }

        updateDateAndTimeButtons(editor, selectedTime);
        editor.dateButton.setOnClickListener(view -> showDatePicker(editor, selectedTime));
        editor.timeButton.setOnClickListener(view -> showTimePicker(editor, selectedTime));
        editor.pasteMetadataButton.setOnClickListener(view -> pasteMetadata(editor));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(source == null || duplicate
                        ? R.string.new_schedule
                        : R.string.edit_schedule)
                .setView(editor.getRoot())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save_schedule, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> saveSchedule(
                        dialog,
                        editor,
                        selectedTime,
                        duplicate ? null : source
                ))
        );
        dialog.show();
    }

    private void configurePlatformSpinner(DialogScheduleEditorBinding editor) {
        List<String> names = new ArrayList<>();
        for (SocialPlatform platform : SocialPlatform.values()) {
            names.add(platform.getDisplayName());
        }
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this,
                R.layout.item_spinner_text,
                names
        );
        spinnerAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        editor.platformSpinner.setAdapter(spinnerAdapter);
    }

    private void configureVisibilitySpinner(DialogScheduleEditorBinding editor) {
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.visibility_public));
        names.add(getString(R.string.visibility_unlisted));
        names.add(getString(R.string.visibility_private));
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this,
                R.layout.item_spinner_text,
                names
        );
        spinnerAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        editor.visibilitySpinner.setAdapter(spinnerAdapter);
    }

    private void showDatePicker(DialogScheduleEditorBinding editor, Calendar selectedTime) {
        DatePickerDialog picker = new DatePickerDialog(
                this,
                (view, year, month, day) -> {
                    selectedTime.set(Calendar.YEAR, year);
                    selectedTime.set(Calendar.MONTH, month);
                    selectedTime.set(Calendar.DAY_OF_MONTH, day);
                    updateDateAndTimeButtons(editor, selectedTime);
                },
                selectedTime.get(Calendar.YEAR),
                selectedTime.get(Calendar.MONTH),
                selectedTime.get(Calendar.DAY_OF_MONTH)
        );
        picker.getDatePicker().setMinDate(System.currentTimeMillis() - 60_000L);
        picker.show();
    }

    private void showTimePicker(DialogScheduleEditorBinding editor, Calendar selectedTime) {
        TimePickerDialog picker = new TimePickerDialog(
                this,
                (view, hour, minute) -> {
                    selectedTime.set(Calendar.HOUR_OF_DAY, hour);
                    selectedTime.set(Calendar.MINUTE, minute);
                    selectedTime.set(Calendar.SECOND, 0);
                    selectedTime.set(Calendar.MILLISECOND, 0);
                    updateDateAndTimeButtons(editor, selectedTime);
                },
                selectedTime.get(Calendar.HOUR_OF_DAY),
                selectedTime.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(this)
        );
        picker.show();
    }

    private void updateDateAndTimeButtons(
            DialogScheduleEditorBinding editor,
            Calendar selectedTime
    ) {
        editor.dateButton.setText(ScheduleTimeFormatter.date(selectedTime.getTimeInMillis()));
        editor.timeButton.setText(ScheduleTimeFormatter.time(selectedTime.getTimeInMillis()));
    }

    private void pasteMetadata(DialogScheduleEditorBinding editor) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null
                || !clipboard.hasPrimaryClip()
                || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence clipboardText = clipboard.getPrimaryClip()
                .getItemAt(0)
                .coerceToText(this);
        PublicationMetadataParser.ParsedMetadata parsed = PublicationMetadataParser.parse(
                clipboardText == null ? "" : clipboardText.toString()
        );
        if (parsed.getTitle().isEmpty()
                && parsed.getDescription().isEmpty()
                && parsed.getHashtags().isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_LONG).show();
            return;
        }
        editor.titleInput.setText(parsed.getTitle());
        editor.descriptionInput.setText(parsed.getDescription());
        editor.hashtagsInput.setText(parsed.getHashtags());
        Toast.makeText(this, R.string.metadata_pasted, Toast.LENGTH_SHORT).show();
    }

    private void saveSchedule(
            AlertDialog dialog,
            DialogScheduleEditorBinding editor,
            Calendar selectedTime,
            PublicationSchedule existing
    ) {
        long scheduledAt = selectedTime.getTimeInMillis();
        boolean alreadyPublished = existing != null && existing.isPublished();
        if (!alreadyPublished && scheduledAt <= System.currentTimeMillis()) {
            Toast.makeText(this, R.string.schedule_must_be_future, Toast.LENGTH_LONG).show();
            return;
        }

        SocialPlatform platform = SocialPlatform.values()[
                editor.platformSpinner.getSelectedItemPosition()
        ];
        PublicationSchedule schedule = new PublicationSchedule(
                existing == null ? UUID.randomUUID().toString() : existing.getId(),
                videoUri,
                videoName,
                platform.getKey(),
                scheduledAt,
                editor.titleInput.getText().toString(),
                editor.descriptionInput.getText().toString(),
                editor.hashtagsInput.getText().toString(),
                visibilityFromPosition(editor.visibilitySpinner.getSelectedItemPosition()),
                existing == null ? System.currentTimeMillis() : existing.getCreatedAtMillis(),
                alreadyPublished
        );
        PublicationScheduleRepository.save(this, schedule);
        PublicationReminderScheduler.schedule(this, schedule);
        dialog.dismiss();
        refreshSchedules();
        if (!schedule.isPublished()) {
            requestNotificationPermissionIfNeeded();
        }
        Toast.makeText(this, R.string.schedule_saved, Toast.LENGTH_SHORT).show();
    }

    private void updatePublishedState(PublicationSchedule schedule, boolean published) {
        PublicationSchedule updated = PublicationScheduleRepository.setPublished(
                this,
                schedule.getId(),
                published
        );
        if (updated == null) {
            return;
        }
        if (published) {
            PublicationReminderScheduler.cancel(this, updated);
            SavedVideoAdapter.markPlatformShared(
                    this,
                    updated.getVideoUri(),
                    updated.getPlatformKey()
            );
        } else {
            PublicationReminderScheduler.schedule(this, updated);
        }
        refreshSchedules();
        Toast.makeText(
                this,
                published ? R.string.schedule_marked_published : R.string.schedule_marked_pending,
                Toast.LENGTH_SHORT
        ).show();
    }

    private void confirmDeleteSchedule(PublicationSchedule schedule) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.delete_schedule)
                .setMessage(getString(
                        R.string.delete_schedule_message,
                        schedule.getPlatform().getDisplayName(),
                        ScheduleTimeFormatter.dateTime(schedule.getScheduledAtMillis())
                ))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (ignored, which) -> {
                    PublicationSchedule removed = PublicationScheduleRepository.delete(
                            this,
                            schedule.getId()
                    );
                    if (removed != null) {
                        PublicationReminderScheduler.cancel(this, removed);
                        refreshSchedules();
                        Toast.makeText(
                                this,
                                R.string.schedule_deleted,
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                })
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.danger))
        );
        dialog.show();
    }

    private void shareNow(PublicationSchedule schedule) {
        Uri uri;
        try {
            uri = Uri.parse(schedule.getVideoUri());
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.invalid_video, Toast.LENGTH_LONG).show();
            return;
        }

        copyMetadataToClipboard(schedule, false);
        Intent shareIntent = new Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.setClipData(ClipData.newUri(
                getContentResolver(),
                getString(R.string.share_video),
                uri
        ));
        if (!schedule.getTitle().isEmpty()) {
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, schedule.getTitle());
        }
        String metadata = schedule.buildShareText();
        if (!metadata.isEmpty()) {
            shareIntent.putExtra(Intent.EXTRA_TEXT, metadata);
        }

        String packageName = schedule.getPlatform().getPackageName();
        if (packageName != null) {
            Intent directIntent = new Intent(shareIntent).setPackage(packageName);
            try {
                startActivity(directIntent);
                Toast.makeText(this, R.string.finish_publication_hint, Toast.LENGTH_LONG).show();
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                Toast.makeText(this, R.string.target_app_not_installed, Toast.LENGTH_LONG).show();
            }
        }

        try {
            startActivity(Intent.createChooser(shareIntent, getString(
                    R.string.publish_on_platform,
                    schedule.getPlatform().getDisplayName()
            )));
            Toast.makeText(this, R.string.finish_publication_hint, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, R.string.share_not_supported, Toast.LENGTH_LONG).show();
        }
    }

    private void copyMetadataToClipboard(PublicationSchedule schedule, boolean showMessage) {
        String metadata = schedule.buildShareText();
        if (metadata.isEmpty()) {
            if (showMessage) {
                Toast.makeText(this, R.string.no_metadata_to_copy, Toast.LENGTH_LONG).show();
            }
            return;
        }
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.publication_metadata),
                    metadata
            ));
            if (showMessage) {
                Toast.makeText(this, R.string.metadata_copied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void updateNotificationWarning() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        boolean enabled = manager != null && manager.areNotificationsEnabled();
        if (Build.VERSION.SDK_INT >= 33) {
            enabled = enabled
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        binding.notificationWarning.setVisibility(enabled ? View.GONE : View.VISIBLE);
        AlarmManager alarmManager = getSystemService(AlarmManager.class);
        boolean exactAlarmAvailable = Build.VERSION.SDK_INT < 31
                || (alarmManager != null && alarmManager.canScheduleExactAlarms());
        binding.exactAlarmWarning.setVisibility(
                exactAlarmAvailable ? View.GONE : View.VISIBLE
        );
    }

    private void openNotificationSettings() {
        try {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(settings);
        } catch (ActivityNotFoundException | SecurityException error) {
            requestNotificationPermissionIfNeeded();
        }
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        try {
            Intent settings = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:" + getPackageName()));
            startActivity(settings);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, R.string.notifications_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private int visibilityPosition(String visibility) {
        if (PublicationSchedule.VISIBILITY_UNLISTED.equals(visibility)) {
            return 1;
        }
        if (PublicationSchedule.VISIBILITY_PRIVATE.equals(visibility)) {
            return 2;
        }
        return 0;
    }

    private String visibilityFromPosition(int position) {
        if (position == 1) {
            return PublicationSchedule.VISIBILITY_UNLISTED;
        }
        if (position == 2) {
            return PublicationSchedule.VISIBILITY_PRIVATE;
        }
        return PublicationSchedule.VISIBILITY_PUBLIC;
    }
}
