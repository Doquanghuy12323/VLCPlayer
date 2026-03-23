package com.vlcplayer.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
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
            Bitmap bmp = loadFromMediaStore(ctx, video.getId());
            cache.put(video.getId(), bmp);
            mainHandler.post(() -> {
                if (Long.valueOf(video.getId()).equals(holder.itemView.getTag())) {
                    setThumb(holder, bmp);
                }
            });
        });
    }

    @SuppressWarnings("deprecation")
    private Bitmap loadFromMediaStore(Context ctx, long videoId) {
        ContentResolver cr = ctx.getContentResolver();

        // Phương pháp 1 — API 29+ (Android 10+)
        // loadThumbnail dùng MediaStore cache, KHÔNG đọc file video
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Uri uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId);
                Bitmap bmp = cr.loadThumbnail(uri, new Size(320, 180), null);
                if (bmp != null) return bmp;
            } catch (Exception ignored) {}
        }

        // Phương pháp 2 — dùng video ID lấy từ MediaStore.Video.Thumbnails
        // Hoạt động tốt mọi Android version, đọc từ thumbnail database sẵn có
        try {
            Bitmap bmp = MediaStore.Video.Thumbnails.getThumbnail(
                cr,
                videoId,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            );
            if (bmp != null) return bmp;
        } catch (Exception ignored) {}

        // Phương pháp 3 — query trực tiếp bảng Thumbnails
        try {
            String[] proj = { MediaStore.Video.Thumbnails.DATA };
            String sel    = MediaStore.Video.Thumbnails.VIDEO_ID + "=?";
            String[] args = { String.valueOf(videoId) };

            android.database.Cursor c = cr.query(
                MediaStore.Video.Thumbnails.EXTERNAL_CONTENT_URI,
                proj, sel, args, null
            );
            if (c != null) {
                if (c.moveToFirst()) {
                    String path = c.getString(0);
                    c.close();
                    if (path != null && !path.isEmpty()) {
                        return android.graphics.BitmapFactory.decodeFile(path);
                    }
                } else {
                    c.close();
                }
            }
        } catch (Exception ignored) {}

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
