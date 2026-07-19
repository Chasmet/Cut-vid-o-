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

public final class VideoCollectionAdapter
        extends RecyclerView.Adapter<VideoCollectionAdapter.CollectionViewHolder> {

    public interface Actions {
        void openCollection(VideoCollection collection);

        void editNote(VideoCollection collection);

        void renameCollection(VideoCollection collection);

        void shareCollection(VideoCollection collection);

        void deleteCollection(VideoCollection collection);
    }

    private final Context context;
    private final Actions actions;
    private final List<VideoCollection> collections = new ArrayList<>();
    private LibraryDisplaySettings settings;

    public VideoCollectionAdapter(
            Context context,
            LibraryDisplaySettings settings,
            Actions actions
    ) {
        this.context = context;
        this.settings = settings;
        this.actions = actions;
    }

    public void submit(List<VideoCollection> newCollections) {
        collections.clear();
        collections.addAll(newCollections);
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
    public CollectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == LibraryDisplaySettings.MODE_GRID
                ? R.layout.item_video_collection_grid
                : R.layout.item_video_collection;
        View itemView = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new CollectionViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull CollectionViewHolder holder, int position) {
        VideoCollection collection = collections.get(position);
        applySize(holder);
        holder.nameText.setText(collection.getName());
        holder.detailsText.setText(context.getString(
                R.string.collection_details,
                collection.getFolderCount(),
                collection.getVideoCount(),
                TimeFormatter.fileSize(collection.getTotalSizeBytes())
        ));

        FolderNote note = FolderNoteRepository.getCollection(context, collection.getId());
        holder.noteButton.setAlpha(note.isEmpty() ? 0.48f : 1f);
        holder.noteButton.setContentDescription(context.getString(
                note.isEmpty() ? R.string.add_folder_note : R.string.edit_folder_note
        ));

        holder.itemView.setOnClickListener(view -> actions.openCollection(collection));
        holder.noteButton.setOnClickListener(view -> actions.editNote(collection));
        holder.moreButton.setOnClickListener(view -> showActionsMenu(view, collection));
    }

    private void showActionsMenu(View anchor, VideoCollection collection) {
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.inflate(R.menu.menu_video_collection_actions);
        menu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_rename_collection) {
                actions.renameCollection(collection);
            } else if (itemId == R.id.action_share_collection) {
                actions.shareCollection(collection);
            } else if (itemId == R.id.action_delete_collection) {
                actions.deleteCollection(collection);
            } else {
                return false;
            }
            return true;
        });
        menu.show();
    }

    private void applySize(CollectionViewHolder holder) {
        boolean grid = settings.usesGrid();
        int itemSize = settings.getItemSize();
        int heightDp;
        int iconDp;
        float labelSp;
        float nameSp;
        float detailsSp;
        if (grid) {
            if (itemSize == LibraryDisplaySettings.SIZE_SMALL) {
                heightDp = 142;
                iconDp = 40;
                labelSp = 8f;
                nameSp = 12f;
                detailsSp = 8f;
            } else if (itemSize == LibraryDisplaySettings.SIZE_LARGE) {
                heightDp = 210;
                iconDp = 64;
                labelSp = 11f;
                nameSp = 18f;
                detailsSp = 13f;
            } else {
                heightDp = 174;
                iconDp = 52;
                labelSp = 9f;
                nameSp = 15f;
                detailsSp = 10f;
            }
        } else if (itemSize == LibraryDisplaySettings.SIZE_SMALL) {
            heightDp = 96;
            iconDp = 44;
            labelSp = 9f;
            nameSp = 15f;
            detailsSp = 10f;
        } else if (itemSize == LibraryDisplaySettings.SIZE_LARGE) {
            heightDp = 142;
            iconDp = 64;
            labelSp = 11f;
            nameSp = 19f;
            detailsSp = 14f;
        } else {
            heightDp = 116;
            iconDp = 54;
            labelSp = 10f;
            nameSp = 17f;
            detailsSp = 12f;
        }

        ViewGroup.LayoutParams itemParams = holder.itemView.getLayoutParams();
        itemParams.height = dp(heightDp);
        holder.itemView.setLayoutParams(itemParams);
        ViewGroup.LayoutParams iconParams = holder.icon.getLayoutParams();
        iconParams.width = dp(iconDp);
        iconParams.height = dp(iconDp);
        holder.icon.setLayoutParams(iconParams);
        holder.labelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp);
        holder.nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp);
        holder.detailsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, detailsSp);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return collections.size();
    }

    static final class CollectionViewHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView labelText;
        private final TextView nameText;
        private final TextView detailsText;
        private final ImageButton noteButton;
        private final ImageButton moreButton;

        CollectionViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.collectionIcon);
            labelText = itemView.findViewById(R.id.collectionLabelText);
            nameText = itemView.findViewById(R.id.collectionNameText);
            detailsText = itemView.findViewById(R.id.collectionDetailsText);
            noteButton = itemView.findViewById(R.id.noteButton);
            moreButton = itemView.findViewById(R.id.moreButton);
        }
    }
}
