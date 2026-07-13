package com.chasmet.cutvideo;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chasmet.cutvideo.databinding.ItemPublicationScheduleBinding;

import java.util.ArrayList;
import java.util.List;

public final class VideoScheduleAdapter
        extends RecyclerView.Adapter<VideoScheduleAdapter.ScheduleViewHolder> {

    public interface Actions {
        void publish(PublicationSchedule schedule);

        void edit(PublicationSchedule schedule);

        void duplicate(PublicationSchedule schedule);

        void copyMetadata(PublicationSchedule schedule);

        void setPublished(PublicationSchedule schedule, boolean published);

        void delete(PublicationSchedule schedule);
    }

    private final Context context;
    private final Actions actions;
    private final List<PublicationSchedule> schedules = new ArrayList<>();

    public VideoScheduleAdapter(Context context, Actions actions) {
        this.context = context;
        this.actions = actions;
    }

    public void submit(List<PublicationSchedule> newSchedules) {
        schedules.clear();
        schedules.addAll(newSchedules);
        notifyDataSetChanged();
    }

    public int positionOf(String scheduleId) {
        for (int index = 0; index < schedules.size(); index++) {
            if (schedules.get(index).getId().equals(scheduleId)) {
                return index;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    @NonNull
    @Override
    public ScheduleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPublicationScheduleBinding binding = ItemPublicationScheduleBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new ScheduleViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ScheduleViewHolder holder, int position) {
        PublicationSchedule schedule = schedules.get(position);
        boolean due = !schedule.isPublished()
                && schedule.getScheduledAtMillis() <= System.currentTimeMillis();

        holder.binding.platformText.setText(schedule.getPlatform().getDisplayName());
        holder.binding.dateText.setText(context.getString(
                R.string.schedule_local_time,
                ScheduleTimeFormatter.dateTime(schedule.getScheduledAtMillis())
        ));
        holder.binding.visibilityText.setText(visibilityLabel(schedule.getVisibility()));

        String title = schedule.getTitle().isEmpty()
                ? schedule.getVideoName()
                : schedule.getTitle();
        holder.binding.scheduleTitleText.setText(title);

        String metadata = schedule.buildShareText();
        holder.binding.metadataText.setText(
                metadata.isEmpty() ? context.getString(R.string.no_metadata) : metadata
        );
        holder.binding.metadataText.setAlpha(metadata.isEmpty() ? 0.65f : 1f);

        if (schedule.isPublished()) {
            holder.binding.statusText.setText(R.string.schedule_status_published);
            holder.binding.statusText.setTextColor(context.getColor(R.color.text_secondary));
        } else if (due) {
            holder.binding.statusText.setText(R.string.schedule_status_due);
            holder.binding.statusText.setTextColor(context.getColor(R.color.danger));
        } else {
            holder.binding.statusText.setText(R.string.schedule_status_scheduled);
            holder.binding.statusText.setTextColor(context.getColor(R.color.teal));
        }

        holder.binding.publishedCheck.setOnCheckedChangeListener(null);
        holder.binding.publishedCheck.setChecked(schedule.isPublished());
        holder.binding.publishedCheck.setOnCheckedChangeListener((button, checked) ->
                actions.setPublished(schedule, checked)
        );

        holder.binding.publishButton.setText(
                schedule.isPublished() ? R.string.publish_again : R.string.publish_now
        );
        holder.binding.publishButton.setOnClickListener(view -> actions.publish(schedule));
        holder.binding.editButton.setOnClickListener(view -> actions.edit(schedule));
        holder.binding.duplicateButton.setOnClickListener(view -> actions.duplicate(schedule));
        holder.binding.copyButton.setOnClickListener(view -> actions.copyMetadata(schedule));
        holder.binding.deleteButton.setOnClickListener(view -> actions.delete(schedule));
    }

    private String visibilityLabel(String visibility) {
        if (PublicationSchedule.VISIBILITY_PRIVATE.equals(visibility)) {
            return context.getString(R.string.visibility_private);
        }
        if (PublicationSchedule.VISIBILITY_UNLISTED.equals(visibility)) {
            return context.getString(R.string.visibility_unlisted);
        }
        return context.getString(R.string.visibility_public);
    }

    @Override
    public int getItemCount() {
        return schedules.size();
    }

    static final class ScheduleViewHolder extends RecyclerView.ViewHolder {
        private final ItemPublicationScheduleBinding binding;

        ScheduleViewHolder(ItemPublicationScheduleBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
