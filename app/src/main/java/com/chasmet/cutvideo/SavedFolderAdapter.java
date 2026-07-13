package com.chasmet.cutvideo;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chasmet.cutvideo.databinding.ItemSavedFolderBinding;

import java.util.ArrayList;
import java.util.List;

public final class SavedFolderAdapter
        extends RecyclerView.Adapter<SavedFolderAdapter.FolderViewHolder> {

    public interface Actions {
        void openFolder(SavedVideoFolder folder);

        void renameFolder(SavedVideoFolder folder);

        void shareFolder(SavedVideoFolder folder);

        void deleteFolder(SavedVideoFolder folder);
    }

    private final Context context;
    private final Actions actions;
    private final List<SavedVideoFolder> folders = new ArrayList<>();

    public SavedFolderAdapter(Context context, Actions actions) {
        this.context = context;
        this.actions = actions;
    }

    public void submit(List<SavedVideoFolder> newFolders) {
        folders.clear();
        folders.addAll(newFolders);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSavedFolderBinding binding = ItemSavedFolderBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new FolderViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
        SavedVideoFolder folder = folders.get(position);
        String title = VideoFolderUtils.isLegacy(folder.getKey())
                ? context.getString(R.string.legacy_folder)
                : VideoFolderUtils.displayName(folder.getKey());
        String date = VideoFolderUtils.displayDate(folder.getKey());

        holder.binding.folderNameText.setText(title);
        holder.binding.folderDetailsText.setText(context.getString(
                R.string.folder_details,
                folder.getVideoCount(),
                TimeFormatter.fileSize(folder.getTotalSizeBytes())
        ));
        holder.binding.folderDateText.setText(date);
        holder.binding.folderDateText.setVisibility(date.isEmpty() ? View.GONE : View.VISIBLE);
        holder.binding.getRoot().setOnClickListener(view -> actions.openFolder(folder));
        holder.binding.renameFolderButton.setOnClickListener(view -> actions.renameFolder(folder));
        holder.binding.shareFolderButton.setOnClickListener(view -> actions.shareFolder(folder));
        holder.binding.deleteFolderButton.setOnClickListener(view -> actions.deleteFolder(folder));
    }

    @Override
    public int getItemCount() {
        return folders.size();
    }

    static final class FolderViewHolder extends RecyclerView.ViewHolder {
        private final ItemSavedFolderBinding binding;

        FolderViewHolder(ItemSavedFolderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
