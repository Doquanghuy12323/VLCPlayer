package com.vlcplayer.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VH> {

    public interface OnVideoClickListener {
        void onVideoClick(VideoItem video);
    }

    // Gioi han cache thumbnail toi da 30 anh (khong de vo han)
    private static final int MAX_CACHE = 30;
    private final LinkedHashMap<Long, Bitmap> thumbCache =
        new LinkedHashMap<Long, Bitmap>(MAX_CACHE, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> e) {
                if (size() > MAX_CACHE) {
                    if (e.getValue() != null && !e.getValue().isRecycled())
                        e.getValue().recycle();
                    return true;
                }
                return false;
            }
        };

    private final List<VideoItem> videoList;
    private final OnVideoClickListener listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VideoAdapter(List<VideoItem> list, OnVideoClickListener l) {
        this.videoList = list;
        this.listener  = l;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_video, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        VideoItem item = videoList.get(position);
        h.tvName.setText(item.getName());
        h.tvInfo.setText(formatDur(item.getDuration()) + " · " + formatSize(item.getSize()));
        h.ivThumb.setImageResource(android.R.drawable.ic_media_play);
        if (h.btnPlay != null) h.btnPlay.setVisibility(View.GONE);

        // Load thumbnail qua Glide - tu dong quan ly cache
        Glide.with(h.ivThumb.getContext())
            .load(item.getUri())
            .apply(new RequestOptions()
                .frame(3_000_000L)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .override(120, 80)
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play))
            .into(h.ivThumb);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onVideoClick(item);
        });

        h.itemView.setOnLongClickListener(v -> {
            showPreviewDialog(v.getContext(), item);
            return true;
        });

        if (h.btnPlay != null) {
            h.btnPlay.setOnClickListener(v -> {
                if (listener != null) listener.onVideoClick(item);
            });
        }
    }

    private void showPreviewDialog(Context ctx, VideoItem item) {
        Dialog dialog = new Dialog(ctx,
            android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_preview);

        SurfaceView sv = dialog.findViewById(R.id.preview_surface);
        TextView tvTitle = dialog.findViewById(R.id.tv_hint);
        if (tvTitle != null) tvTitle.setText(item.getName());

        android.media.MediaPlayer[] mp = {null};
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(@NonNull SurfaceHolder h) {
                try {
                    mp[0] = new android.media.MediaPlayer();
                    mp[0].setDataSource(ctx, item.getUri());
                    mp[0].setDisplay(h);
                    mp[0].prepareAsync();
                    mp[0].setOnPreparedListener(p -> {
                        p.seekTo(5000);
                        p.start();
                    });
                } catch (Exception ignored) {}
            }
            @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int he) {}
            @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) {
                if (mp[0] != null) {
                    try { mp[0].stop(); mp[0].release(); } catch (Exception ignored) {}
                    mp[0] = null;
                }
            }
        });

        dialog.setOnDismissListener(d -> {
            if (mp[0] != null) {
                try { mp[0].stop(); mp[0].release(); } catch (Exception ignored) {}
                mp[0] = null;
            }
        });
        dialog.show();
    }

    public void updateList(List<VideoItem> newList) {
        videoList.clear();
        videoList.addAll(newList);
        notifyDataSetChanged();
    }

    public void clearCache() {
        for (Bitmap b : thumbCache.values())
            if (b != null && !b.isRecycled()) b.recycle();
        thumbCache.clear();
    }

    private String formatDur(long ms) {
        long m = ms/1000/60; long s = ms/1000%60;
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024*1024*1024) return String.format("%.1f GB", bytes/1024.0/1024/1024);
        if (bytes >= 1024*1024) return String.format("%.0f MB", bytes/1024.0/1024);
        return String.format("%.0f KB", bytes/1024.0);
    }

    @Override public int getItemCount() { return videoList.size(); }

    @Override public long getItemId(int position) {
        return videoList.get(position).getId();
    }

    public static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView tvName, tvInfo;
        ImageButton btnPlay;
        VH(View v) {
            super(v);
            ivThumb = v.findViewById(R.id.iv_thumbnail);
            tvName  = v.findViewById(R.id.tv_video_name);
            tvInfo  = v.findViewById(R.id.tv_duration);
            btnPlay = null; // no play button in layout
        }
    }
}
