package com.chasmet.cutvideo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.chasmet.cutvideo.databinding.ActivityMainBinding;

public final class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ActivityResultLauncher<PickVisualMediaRequest> videoPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        videoPicker = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                this::handleSelectedVideo
        );

        binding.importVideoCard.setOnClickListener(view -> openVideoPicker());
        binding.savedVideosCard.setOnClickListener(view -> startActivity(
                new Intent(this, SavedVideosActivity.class)
        ));
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
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Le sélecteur moderne maintient lui-même l'accès pendant la session.
        }

        Intent editor = new Intent(this, EditorActivity.class);
        editor.putExtra(EditorActivity.EXTRA_VIDEO_URI, uri.toString());
        editor.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(editor);
    }
}

