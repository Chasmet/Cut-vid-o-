package com.chasmet.cutvideo;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public final class SavedFolderAdapter
        extends RecyclerView.Adapter<SavedFolderAdapter.FolderViewHolder> {

    public interface Actions {
        void openFolder(SavedVideoFolder folder);

        void editNote(SavedVideoFolder folder);

        void renameFolder(SavedVideoFolder folder);

        void moveFolder(SavedVideoFolder folder);

        void shareFolder(SavedVideoFolder folder);

        void deleteFolder(SavedVideoFolder folder);
    }

    private final Context context;
    private final Actions actions;
    private final List<SavedVideoFolder> folders = new ArrayList<>();
    private LibraryDisplaySettings settings;

    public SavedFolderAdapter(
            Context context,
            LibraryDisplaySettings settings,
            Actions actions
    ) {
        this.context = context;
        this.settings = settings;
        this.actions = actions;
    }

    public void submit(List<SavedVideoFolder> newFolders) {
        folders.clear();
        folders.addAll(newFolders);
        notifyDataSetChanged();
    }

    public void setSettings(LibraryDisplaySettings settings) {
        this.settings = settings;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return settings.usesGrid()
                ? LibraryDisplaySettings.MODE_GRID
                : LibraryDisplaySettings.MODE_LIST;
    }

    @NonNull
    @Override
    public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == LibraryDisplaySettings.MODE_GRID
                ? R.layout.item_saved_folder_grid
                : R.layout.item_saved_folder;
        View itemView = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new FolderViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
        SavedVideoFolder folder = folders.get(position);
        String title = VideoFolderUtils.isLegacy(folder.getKey())
                ? context.getString(R.string.legacy_folder)
                : VideoFolderUtils.displayName(folder.getKey());
        String date = VideoFolderUtils.displayDate(folder.getKey());

        applySize(holder);
        holder.nameText.setText(title);
        holder.detailsText.setText(context.getString(
                R.string.folder_details,
                folder.getVideoCount(),
                TimeFormatter.fileSize(folder.getTotalSizeBytes())
        ));
        holder.dateText.setText(date);
        holder.dateText.setVisibility(date.isEmpty() ? View.GONE : View.VISIBLE);

        FolderNote note = FolderNoteRepository.getFolder(context, folder.getKey());
        holder.noteButton.setAlpha(note.isEmpty() ? 0.48f : 1f);
        holder.noteButton.setContentDescription(context.getString(
                note.isEmpty() ? R.string.add_folder_note : R.string.edit_folder_note
        ));

        holder.itemView.setOnClickListener(view -> actions.openFolder(folder));
        holder.noteButton.setOnClickListener(view -> actions.editNote(folder));
        holder.moreButton.setOnClickListener(view -> showActionsMenu(view, folder));
    }

    private void showActionsMenu(View anchor, SavedVideoFolder folder) {
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.inflate(R.menu.menu_saved_folder_actions);
        menu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_rename_folder) {
                actions.renameFolder(folder);
            } else if (itemId == R.id.action_move_folder) {
                actions.moveFolder(folder);
            } else if (itemId == R.id.action_share_folder) {
                actions.shareFolder(folder);
            } else if (itemId == R.id.action_delete_folder) {
                actions.deleteFolder(folder);
            } else {
                return false;
            }
            return true;
        });
        menu.show();
    }

    private void applySize(FolderViewHolder holder) {
        boolean grid = settings.usesGrid();
        int itemSize = settings.getItemSize();
        int heightDp;
        int iconDp;
        float nameSp;
        float detailsSp;
        float dateSp;
        if (grid) {
            if (itemSize == LibraryDisplaySettings.SIZE_SMALL) {
                heightDp = 142;
                iconDp = 40;
                nameSp = 12f;
                detailsSp = 9f;
                dateSp = 8f;
            } else if (itemSize == LibraryDisplaySettings.SIZE_LARGE) {
                heightDp = 210;
                iconDp = 64;
                nameSp = 18f;
                detailsSp = 13f;
                dateSp = 11f;
            } else {
                heightDp = 174;
                iconDp = 52;
                nameSp = 15f;
                detailsSp = 11f;
                dateSp = 10f;
            }
        } else if (itemSize == LibraryDisplaySettings.SIZE_SMALL) {
            heightDp = 96;
            iconDp = 44;
            nameSp = 15f;
            detailsSp = 11f;
            dateSp = 10f;
        } else if (itemSize == LibraryDisplaySettings.SIZE_LARGE) {
            heightDp = 142;
            iconDp = 64;
            nameSp = 19f;
            detailsSp = 14f;
            dateSp = 13f;
        } else {
            heightDp = 116;
            iconDp = 54;
            nameSp = 17f;
            detailsSp = 13f;
            dateSp = 12f;
        }

        ViewGroup.LayoutParams itemParams = holder.itemView.getLayoutParams();
        itemParams.height = dp(heightDp);
        holder.itemView.setLayoutParams(itemParams);
        ViewGroup.LayoutParams iconParams = holder.icon.getLayoutParams();
        iconParams.width = dp(iconDp);
        iconParams.height = dp(iconDp);
        holder.icon.setLayoutParams(iconParams);
        holder.nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp);
        holder.detailsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, detailsSp);
        holder.dateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, dateSp);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return folders.size();
    }

    static final class FolderViewHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView nameText;
        private final TextView detailsText;
        private final TextView dateText;
        private final ImageButton noteButton;
        private final ImageButton moreButton;

        FolderViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.folderIcon);
            nameText = itemView.findViewById(R.id.folderNameText);
            detailsText = itemView.findViewById(R.id.folderDetailsText);
            dateText = itemView.findViewById(R.id.folderDateText);
            noteButton = itemView.findViewById(R.id.noteButton);
            moreButton = itemView.findViewById(R.id.moreButton);
        }
    }
}
