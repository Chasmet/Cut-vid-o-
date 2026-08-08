package com.chasmet.cutvideo;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.chasmet.cutvideo.databinding.ActivityChatgptAssistantBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class ChatGptAssistantActivity extends AppCompatActivity {

    private static final String CHATGPT_PACKAGE = "com.openai.chatgpt";

    private ActivityChatgptAssistantBinding binding;
    private final Calendar selectedTime = Calendar.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatgptAssistantBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        selectedTime.add(Calendar.HOUR_OF_DAY, 1);
        selectedTime.set(Calendar.SECOND, 0);
        selectedTime.set(Calendar.MILLISECOND, 0);

        configureAccountSpinner();
        configurePlatformSpinner();
        updateDateTimeLabels();

        binding.backButton.setOnClickListener(view -> finish());
        binding.dateButton.setOnClickListener(view -> chooseDate());
        binding.timeButton.setOnClickListener(view -> chooseTime());
        binding.accountSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> configurePlatformSpinner()));
        binding.openChatgptButton.setOnClickListener(view -> prepareAndOpenChatGpt());
        binding.copyPromptButton.setOnClickListener(view -> copyPromptOnly());
    }

    private void configureAccountSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                R.layout.item_spinner_text,
                new String[]{"CHKNOIRSHADOW", "QG"}
        );
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        binding.accountSpinner.setAdapter(adapter);
    }

    private void configurePlatformSpinner() {
        boolean qg = binding.accountSpinner.getSelectedItemPosition() == 1;
        List<String> platforms = new ArrayList<>();
        platforms.add("YouTube");
        platforms.add("TikTok");
        if (!qg) {
            platforms.add("Instagram");
            platforms.add("X");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                R.layout.item_spinner_text,
                platforms
        );
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        binding.platformSpinner.setAdapter(adapter);
    }

    private void chooseDate() {
        DatePickerDialog picker = new DatePickerDialog(
                this,
                (view, year, month, day) -> {
                    selectedTime.set(Calendar.YEAR, year);
                    selectedTime.set(Calendar.MONTH, month);
                    selectedTime.set(Calendar.DAY_OF_MONTH, day);
                    updateDateTimeLabels();
                },
                selectedTime.get(Calendar.YEAR),
                selectedTime.get(Calendar.MONTH),
                selectedTime.get(Calendar.DAY_OF_MONTH)
        );
        picker.getDatePicker().setMinDate(System.currentTimeMillis() - 60_000L);
        picker.show();
    }

    private void chooseTime() {
        TimePickerDialog picker = new TimePickerDialog(
                this,
                (view, hour, minute) -> {
                    selectedTime.set(Calendar.HOUR_OF_DAY, hour);
                    selectedTime.set(Calendar.MINUTE, minute);
                    selectedTime.set(Calendar.SECOND, 0);
                    selectedTime.set(Calendar.MILLISECOND, 0);
                    updateDateTimeLabels();
                },
                selectedTime.get(Calendar.HOUR_OF_DAY),
                selectedTime.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(this)
        );
        picker.show();
    }

    private void updateDateTimeLabels() {
        binding.dateButton.setText(new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(selectedTime.getTime()));
        binding.timeButton.setText(new SimpleDateFormat("HH:mm", Locale.FRANCE).format(selectedTime.getTime()));
    }

    private String buildPrompt() {
        String fileName = binding.videoNameInput.getText().toString().trim();
        String subject = binding.subjectInput.getText().toString().trim();
        String account = String.valueOf(binding.accountSpinner.getSelectedItem());
        String platform = String.valueOf(binding.platformSpinner.getSelectedItem());

        if (fileName.isEmpty()) {
            throw new IllegalArgumentException(getString(R.string.chatgpt_file_required));
        }

        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(selectedTime.getTime());
        String time = new SimpleDateFormat("HH:mm", Locale.FRANCE).format(selectedTime.getTime());

        return "Utilise le plugin Cut Vidéo pour préparer cette publication.\n"
                + "Fichier: " + fileName + "\n"
                + (subject.isEmpty() ? "" : "Sujet: " + subject + "\n")
                + "Compte: " + account + "\n"
                + "Réseau: " + platform + "\n"
                + "Date: " + date + "\n"
                + "Heure: " + time + "\n"
                + "Règles: les métadonnées doivent correspondre au fichier, titre + description + hashtags <= 100 caractères au total, 5 hashtags maximum. Programme la publication et crée la fiche Cut Vidéo.";
    }

    private void copyPromptOnly() {
        try {
            copyToClipboard(buildPrompt());
            Toast.makeText(this, R.string.chatgpt_prompt_copied, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void prepareAndOpenChatGpt() {
        final String prompt;
        try {
            prompt = buildPrompt();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        copyToClipboard(prompt);
        Intent direct = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, prompt)
                .setPackage(CHATGPT_PACKAGE);
        try {
            startActivity(direct);
            Toast.makeText(this, R.string.chatgpt_prompt_ready, Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            Intent chooser = Intent.createChooser(
                    new Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, prompt),
                    getString(R.string.open_chatgpt)
            );
            startActivity(chooser);
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Cut Vidéo → ChatGPT", text));
        }
    }
}
