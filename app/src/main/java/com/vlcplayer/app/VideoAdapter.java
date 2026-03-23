package com.vlcplayer.app;

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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(VideoItem video);
    }

    private final List<VideoItem> videoList;
    private final OnVideoClickListener listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

        // Reset thumbnail
        holder.ivThumbnail.setImageResource(android.R.drawable.ic_media_play);
        holder.ivThumbnail.setColorFilter(0xFFE94560);

        // Load thumbnail bất đồng bộ
        String path = video.getPath();
        if (path != null && !path.isEmpty()) {
            holder.itemView.setTag(video.getId());
            executor.execute(() -> {
                Bitmap thumb = extractThumbnail(path);
                mainHandler.post(() -> {
                    // Kiểm tra view chưa bị recycle
                    if (holder.itemView.getTag() != null
                            && holder.itemView.getTag().equals(video.getId())
                            && thumb != null) {
                        holder.ivThumbnail.setColorFilter(null);
                        holder.ivThumbnail.setImageBitmap(thumb);
                    }
                });
            });
        }

        holder.itemView.setOnClickListener(v -> listener.onVideoClick(video));
    }

    private Bitmap extractThumbnail(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            // Lấy frame tại giây thứ 3 (tránh màn đen đầu video)
            Bitmap bmp = retriever.getFrameAtTime(
                3_000_000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            );
            if (bmp == null) {
                bmp = retriever.getFrameAtTime(0);
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
