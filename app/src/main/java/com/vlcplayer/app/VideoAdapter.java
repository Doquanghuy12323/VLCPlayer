package com.vlcplayer.app;

import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(VideoItem video);
    }

    private final List<VideoItem> videoList;
    private final OnVideoClickListener listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Long, Bitmap> thumbCache = new HashMap<>();

    public VideoAdapter(List<VideoItem> videoList, OnVideoClickListener listener) {
        this.videoList = videoList;
        this.listener  = listener;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoItem video = videoList.get(position);
        holder.tvName.setText(video.getName());
        holder.tvDuration.setText(video.getFormattedDuration());
        holder.tvSize.setText(video.getFormattedSize());
        holder.itemView.setTag(video.getId());
        holder.itemView.setOnClickListener(v -> listener.onVideoClick(video));

        // Kiểm tra cache
        if (thumbCache.containsKey(video.getId())) {
            applyThumb(holder, thumbCache.get(video.getId()));
            return;
        }

        // Default icon trước
        applyThumb(holder, null);

        // Load async
        executor.execute(() -> {
            Bitmap bmp = loadThumbnail(video);
            thumbCache.put(video.getId(), bmp);
            mainHandler.post(() -> {
                if (Long.valueOf(video.getId()).equals(holder.itemView.getTag())) {
                    applyThumb(holder, bmp);
                }
            });
        });
    }

    private void applyThumb(VideoViewHolder holder, Bitmap bmp) {
        if (bmp != null) {
            holder.ivThumbnail.clearColorFilter();
            holder.ivThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            holder.ivThumbnail.setImageBitmap(bmp);
        } else {
            holder.ivThumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            holder.ivThumbnail.setImageResource(android.R.drawable.ic_media_play);
            holder.ivThumbnail.setColorFilter(0xFFE94560);
        }
    }

    private Bitmap loadThumbnail(VideoItem video) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ — dùng ThumbnailUtils với File path
                String path = video.getPath();
                if (path != null && !path.isEmpty()) {
                    return ThumbnailUtils.createVideoThumbnail(
                        new File(path),
                        new Size(320, 180),
                        null
                    );
                }
            }
            // Fallback cho Android < 10
            String path = video.getPath();
            if (path != null && !path.isEmpty()) {
                return ThumbnailUtils.createVideoThumbnail(
                    path,
                    MediaStore.Images.Thumbnails.MINI_KIND
                );
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    @Override
    public int getItemCount() { return videoList.size(); }

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvDuration, tvSize;
        ImageView ivThumbnail;

        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName      = itemView.findViewById(R.id.tv_video_name);
            tvDuration  = itemView.findViewById(R.id.tv_duration);
            tvSize      = itemView.findViewById(R.id.tv_size);
            ivThumbnail = itemView.findViewById(R.id.iv_thumbnail);
        }
    }
}
