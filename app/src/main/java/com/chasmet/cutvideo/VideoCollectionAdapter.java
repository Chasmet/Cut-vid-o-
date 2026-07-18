package com.chasmet.cutvideo;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chasmet.cutvideo.databinding.ItemVideoCollectionBinding;

import java.util.ArrayList;
import java.util.List;

public final class VideoCollectionAdapter
        extends RecyclerView.Adapter<VideoCollectionAdapter.CollectionViewHolder> {

    public interface Actions {
        void openCollection(VideoCollection collection);

        void renameCollection(VideoCollection collection);

        void shareCollection(VideoCollection collection);

        void deleteCollection(VideoCollection collection);
    }

    private final Context context;
    private final Actions actions;
    private final List<VideoCollection> collections = new ArrayList<>();

    public VideoCollectionAdapter(Context context, Actions actions) {
        this.context = context;
        this.actions = actions;
    }

    public void submit(List<VideoCollection> newCollections) {
        collections.clear();
        collections.addAll(newCollections);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CollectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemVideoCollectionBinding binding = ItemVideoCollectionBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new CollectionViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull CollectionViewHolder holder, int position) {
        VideoCollection collection = collections.get(position);
        holder.binding.collectionNameText.setText(collection.getName());
        holder.binding.collectionDetailsText.setText(context.getString(
                R.string.collection_details,
                collection.getFolderCount(),
                collection.getVideoCount(),
                TimeFormatter.fileSize(collection.getTotalSizeBytes())
        ));
        holder.binding.getRoot().setOnClickListener(view -> actions.openCollection(collection));
        holder.binding.renameCollectionButton.setOnClickListener(
                view -> actions.renameCollection(collection)
        );
        holder.binding.shareCollectionButton.setOnClickListener(
                view -> actions.shareCollection(collection)
        );
        holder.binding.deleteCollectionButton.setOnClickListener(
                view -> actions.deleteCollection(collection)
        );
    }

    @Override
    public int getItemCount() {
        return collections.size();
    }

    static final class CollectionViewHolder extends RecyclerView.ViewHolder {
        private final ItemVideoCollectionBinding binding;

        CollectionViewHolder(ItemVideoCollectionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
