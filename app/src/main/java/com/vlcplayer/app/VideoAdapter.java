package com.vlcplayer.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
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

    private static final String TAG = "VideoAdapter";

    public interface OnVideoClickListener {
        void onVideoClick(VideoItem video);
    }

    private final List<VideoItem> videoList;
    private final OnVideoClickListener listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Long, Bitmap> cache = new HashMap<>();

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
        holder.itemView.setTag(video.getId());
        holder.itemView.setOnClickListener(v -> listener.onVideoClick(video));

        if (cache.containsKey(video.getId())) {
            setThumb(holder, cache.get(video.getId()));
            return;
        }

        setThumb(holder, null);
        Context ctx = holder.itemView.getContext().getApplicationContext();

        executor.execute(() -> {
            Bitmap bmp = loadThumb(ctx, video);
            cache.put(video.getId(), bmp);
            mainHandler.post(() -> {
                if (Long.valueOf(video.getId()).equals(holder.itemView.getTag())) {
                    setThumb(holder, bmp);
                }
            });
        });
    }

    private Bitmap loadThumb(Context ctx, VideoItem video) {

        // Phương pháp 1: ContentResolver.loadThumbnail (API 29+ / Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                Bitmap bmp = cr.loadThumbnail(video.getUri(), new Size(320, 180), null);
                if (bmp != null) {
                    Log.d(TAG, "Method1 OK: " + video.getName());
                    return bmp;
                }
            } catch (Exception e) {
                Log.w(TAG, "Method1 fail: " + e.getMessage());
            }
        }

        // Phương pháp 2: MediaMetadataRetriever với content URI
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(ctx, video.getUri());
            Bitmap bmp = mmr.getFrameAtTime(
                2_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            );
            mmr.release();
            if (bmp != null) {
                Log.d(TAG, "Method2 OK: " + video.getName());
                return bmp;
            }
        } catch (Exception e) {
            Log.w(TAG, "Method2 fail: " + e.getMessage());
        }

        // Phương pháp 3: MediaMetadataRetriever với file path
        try {
            String path = video.getPath();
            if (path != null && !path.isEmpty()) {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(path);
                Bitmap bmp = mmr.getFrameAtTime(
                    2_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                );
                mmr.release();
                if (bmp != null) {
                    Log.d(TAG, "Method3 OK: " + video.getName());
                    return bmp;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Method3 fail: " + e.getMessage());
        }

        Log.e(TAG, "All methods failed: " + video.getName());
        return null;
    }

    private void setThumb(VideoViewHolder h, Bitmap bmp) {
        if (bmp != null) {
            h.ivThumbnail.clearColorFilter();
            h.ivThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.ivThumbnail.setImageBitmap(bmp);
        } else {
            h.ivThumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            h.ivThumbnail.setImageResource(android.R.drawable.ic_media_play);
            h.ivThumbnail.setColorFilter(0xFFE94560);
        }
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
