package com.vlcplayer.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import java.util.List;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(VideoItem video);
    }

    private final List<VideoItem> videoList;
    private final OnVideoClickListener listener;

    public VideoAdapter(List<VideoItem> videoList, OnVideoClickListener listener) {
        this.videoList = videoList;
        this.listener  = listener;
    }

    @NonNull @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoItem video = videoList.get(position);
        holder.tvName.setText(video.getName());
        holder.tvDuration.setText(video.getFormattedDuration());
        holder.tvSize.setText(video.getFormattedSize());
        holder.itemView.setOnClickListener(v -> listener.onVideoClick(video));

        // Reset về trạng thái mặc định trước khi load
        holder.ivThumbnail.setImageTintList(null);
        holder.ivThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);

        Glide.with(holder.itemView.getContext())
            .asBitmap()
            .load(video.getUri())
            .apply(RequestOptions.frameOf(3_000_000L).centerCrop())
            .listener(new RequestListener<android.graphics.Bitmap>() {
                @Override
                public boolean onLoadFailed(@Nullable GlideException e,
                        Object model, Target<android.graphics.Bitmap> target,
                        boolean isFirstResource) {
                    // Load thất bại → hiện icon play màu accent
                    holder.ivThumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    holder.ivThumbnail.setColorFilter(0xFFE94560);
                    return false;
                }
                @Override
                public boolean onResourceReady(android.graphics.Bitmap resource,
                        Object model, Target<android.graphics.Bitmap> target,
                        DataSource dataSource, boolean isFirstResource) {
                    // Load thành công → xóa tint để hiện đúng màu thumbnail
                    holder.ivThumbnail.clearColorFilter();
                    holder.ivThumbnail.setImageTintList(null);
                    return false;
                }
            })
            .into(holder.ivThumbnail);
    }

    @Override
    public void onViewRecycled(@NonNull VideoViewHolder holder) {
        super.onViewRecycled(holder);
        Glide.with(holder.itemView.getContext()).clear(holder.ivThumbnail);
        holder.ivThumbnail.setImageTintList(null);
    }

    @Override public int getItemCount() { return videoList.size(); }

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvDuration, tvSize;
        ImageView ivThumbnail;
        VideoViewHolder(@NonNull View v) {
            super(v);
            tvName      = v.findViewById(R.id.tv_video_name);
            tvDuration  = v.findViewById(R.id.tv_duration);
            tvSize      = v.findViewById(R.id.tv_size);
            ivThumbnail = v.findViewById(R.id.iv_thumbnail);
        }
    }
}
