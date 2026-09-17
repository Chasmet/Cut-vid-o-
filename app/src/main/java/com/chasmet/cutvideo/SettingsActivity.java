package com.chasmet.cutvideo;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.chasmet.cutvideo.databinding.ActivitySettingsBinding;

public final class SettingsActivity extends AppCompatActivity {
    private ActivitySettingsBinding binding;
    private boolean firstResume = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.backButton.setOnClickListener(v -> finish());
        binding.currentVersion.setText("Version installée : v" + BuildConfig.VERSION_NAME);
        binding.checkUpdateButton.setOnClickListener(v -> checkForUpdate(true));
        checkForUpdate(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppUpdateManager.resumePendingUpdate(this);

        if (!firstResume) {
            binding.currentVersion.setText("Version installée : v" + BuildConfig.VERSION_NAME);
        }
        firstResume = false;
    }

    private void checkForUpdate(boolean interactive) {
        binding.checkUpdateButton.setEnabled(false);
        binding.checkUpdateButton.setText("VÉRIFICATION…");

        AppUpdateManager.check(this, interactive, (message, available) -> {
            binding.updateStatus.setText(message);
            binding.checkUpdateButton.setEnabled(true);
            binding.checkUpdateButton.setText(
                    available ? "INSTALLER LA MISE À JOUR" : "VÉRIFIER LES MISES À JOUR"
            );
        });
    }
}
