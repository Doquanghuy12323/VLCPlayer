package com.vlcplayer.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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
    // Cache thumbnail tránh load lại
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

        // Kiểm tra cache trước
        if (thumbCache.containsKey(video.getId())) {
            Bitmap cached = thumbCache.get(video.getId());
            if (cached != null) {
                holder.ivThumbnail.setColorFilter(null);
                holder.ivThumbnail.setImageBitmap(cached);
            } else {
                setDefaultThumb(holder);
            }
            holder.itemView.setOnClickListener(v -> listener.onVideoClick(video));
            return;
        }

        // Chưa có cache → load async
        setDefaultThumb(holder);
        holder.itemView.setTag(video.getId());

        Context ctx = holder.itemView.getContext().getApplicationContext();
        executor.execute(() -> {
            // Dùng URI thay vì path — Android 13 chặn truy cập file trực tiếp
            Bitmap thumb = extractThumbnailFromUri(ctx, video);
            thumbCache.put(video.getId(), thumb);

            mainHandler.post(() -> {
                Object tag = holder.itemView.getTag();
                if (tag != null && tag.equals(video.getId())) {
                    if (thumb != null) {
                        holder.ivThumbnail.setColorFilter(null);
                        holder.ivThumbnail.setImageBitmap(thumb);
                    } else {
                        setDefaultThumb(holder);
                    }
                }
            });
        });

        holder.itemView.setOnClickListener(v -> listener.onVideoClick(video));
    }

    private void setDefaultThumb(VideoViewHolder holder) {
        holder.ivThumbnail.setImageResource(android.R.drawable.ic_media_play);
        holder.ivThumbnail.setColorFilter(0xFFE94560);
    }

    private Bitmap extractThumbnailFromUri(Context ctx, VideoItem video) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            // Dùng content URI — hoạt động tốt trên Android 10+
            retriever.setDataSource(ctx, video.getUri());

            // Lấy frame giây thứ 3, fallback về giây 0
            Bitmap bmp = retriever.getFrameAtTime(
                3_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            );
            if (bmp == null) {
                bmp = retriever.getFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                );
            }
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
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
