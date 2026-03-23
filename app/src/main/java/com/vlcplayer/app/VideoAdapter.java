package com.vlcplayer.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(VideoItem video);
    }

    private final List<VideoItem> videoList;
    private final OnVideoClickListener listener;
    // Pool riêng cho thumbnail — giới hạn 2 thread tránh OOM với file lớn
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);
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

        thumbExecutor.execute(() -> {
            // Dùng Future với timeout 8 giây — file 6GB cần thời gian seek
            Bitmap bmp = loadThumbWithTimeout(ctx, video, 8);
            cache.put(video.getId(), bmp);
            mainHandler.post(() -> {
                if (Long.valueOf(video.getId()).equals(holder.itemView.getTag())) {
                    setThumb(holder, bmp);
                }
            });
        });
    }

    private Bitmap loadThumbWithTimeout(Context ctx, VideoItem video, int timeoutSec) {
        ExecutorService single = Executors.newSingleThreadExecutor();
        Future<Bitmap> future = single.submit(new Callable<Bitmap>() {
            @Override
            public Bitmap call() {
                return extractFrame(ctx, video);
            }
        });
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return null;
        } finally {
            single.shutdownNow();
        }
    }

    private Bitmap extractFrame(Context ctx, VideoItem video) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ctx.getContentResolver()
                     .openFileDescriptor(video.getUri(), "r");
            if (pfd == null) return null;

            mmr.setDataSource(pfd.getFileDescriptor());

            // Lấy duration để seek đến 5% — tránh màn đen đầu video
            String durStr = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = (durStr != null) ? Long.parseLong(durStr) : 0;
            // Seek đến 5% hoặc tối thiểu 1 giây
            long seekUs = Math.max(1_000_000L, durMs * 50L);

            Bitmap bmp = mmr.getFrameAtTime(
                seekUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            );
            if (bmp == null) {
                bmp = mmr.getFrameAtTime(0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            return bmp;

        } catch (Exception e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        }
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
