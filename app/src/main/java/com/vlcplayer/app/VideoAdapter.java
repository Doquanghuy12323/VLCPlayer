package com.vlcplayer.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);
    private final Map<Long, Bitmap> thumbCache = new HashMap<>();

    private Dialog previewDialog = null;
    private MediaPlayer previewPlayer = null;
    private Runnable stopRunnable = null;

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

        holder.itemView.setOnClickListener(v -> {
            stopPreview();
            listener.onVideoClick(video);
        });
        holder.itemView.setOnLongClickListener(v -> {
            showPreview(holder.itemView.getContext(), video);
            return true;
        });

        // Kiểm tra cache trước
        if (thumbCache.containsKey(video.getId())) {
            applyThumb(holder, thumbCache.get(video.getId()));
            return;
        }

        // Reset về default
        applyThumb(holder, null);
        Context appCtx = holder.itemView.getContext().getApplicationContext();

        thumbExecutor.execute(() -> {
            Bitmap bmp = loadThumb(appCtx, video);
            thumbCache.put(video.getId(), bmp);
            handler.post(() -> {
                if (Long.valueOf(video.getId()).equals(holder.itemView.getTag())) {
                    applyThumb(holder, bmp);
                }
            });
        });
    }

    private Bitmap loadThumb(Context ctx, VideoItem video) {
        // Phương pháp 1: Glide frameOf
        try {
            Bitmap bmp = Glide.with(ctx)
                .asBitmap()
                .load(video.getUri())
                .apply(RequestOptions.frameOf(3_000_000L)
                    .diskCacheStrategy(DiskCacheStrategy.DATA))
                .submit(320, 180)
                .get();
            if (bmp != null && !isBlackBitmap(bmp)) return bmp;
        } catch (Exception ignored) {}

        // Phương pháp 2: MediaMetadataRetriever qua FileDescriptor
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ctx.getContentResolver().openFileDescriptor(video.getUri(), "r");
            if (pfd != null) {
                mmr.setDataSource(pfd.getFileDescriptor());

                // Thử nhiều vị trí khác nhau
                long[] timePositions = {3_000_000L, 1_000_000L, 5_000_000L, 10_000_000L, 0L};
                for (long timeUs : timePositions) {
                    Bitmap bmp = mmr.getFrameAtTime(timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (bmp != null && !isBlackBitmap(bmp)) return bmp;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        }

        return null;
    }

    // Kiểm tra bitmap có đen không (> 90% pixel đen)
    private boolean isBlackBitmap(Bitmap bmp) {
        if (bmp == null) return true;
        // Lấy mẫu 10x10 pixel ở giữa
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w == 0 || h == 0) return true;
        int cx = w / 2;
        int cy = h / 2;
        int blackCount = 0;
        int total = 0;
        for (int x = cx - 5; x < cx + 5 && x < w; x++) {
            for (int y = cy - 5; y < cy + 5 && y < h; y++) {
                if (x >= 0 && y >= 0) {
                    int pixel = bmp.getPixel(x, y);
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    if (r < 15 && g < 15 && b < 15) blackCount++;
                    total++;
                }
            }
        }
        return total > 0 && (float) blackCount / total > 0.9f;
    }

    private void applyThumb(VideoViewHolder h, Bitmap bmp) {
        if (bmp != null) {
            h.ivThumbnail.clearColorFilter();
            h.ivThumbnail.setImageTintList(null);
            h.ivThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.ivThumbnail.setImageBitmap(bmp);
        } else {
            h.ivThumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            h.ivThumbnail.setImageResource(android.R.drawable.ic_media_play);
            h.ivThumbnail.setColorFilter(0xFFE94560);
        }
    }

    private void showPreview(Context ctx, VideoItem video) {
        stopPreview();
        previewDialog = new Dialog(ctx);
        previewDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_preview, null);
        previewDialog.setContentView(dialogView);

        Window window = previewDialog.getWindow();
        if (window != null) {
            int w = ctx.getResources().getDisplayMetrics().widthPixels;
            int h = ctx.getResources().getDisplayMetrics().heightPixels;
            window.setLayout(w, h / 2);
            window.setBackgroundDrawableResource(android.R.color.black);
        }

        TextureView textureView = dialogView.findViewById(R.id.texture_preview);
        dialogView.setOnClickListener(v -> stopPreview());
        previewDialog.setOnDismissListener(d -> releasePlayer());
        previewDialog.show();

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                startMediaPlayer(ctx, video.getUri(), new Surface(st), textureView);
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                releasePlayer(); return true;
            }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });

        stopRunnable = this::stopPreview;
        handler.postDelayed(stopRunnable, 10000);
    }

    private void startMediaPlayer(Context ctx, Uri uri, Surface surface, TextureView tv) {
        try {
            previewPlayer = new MediaPlayer();
            previewPlayer.setDataSource(ctx, uri);
            previewPlayer.setSurface(surface);
            previewPlayer.setVolume(0, 0);
            previewPlayer.setOnVideoSizeChangedListener((mp, width, height) -> {
                if (width > 0 && height > 0 && tv.getWidth() > 0) {
                    float scaleY = (float)(tv.getWidth() * height) / (float)(width * tv.getHeight());
                    float scaleX = 1f;
                    if (scaleY < 1f) { scaleX = 1f / scaleY; scaleY = 1f; }
                    tv.setScaleX(scaleX);
                    tv.setScaleY(scaleY);
                }
            });
            previewPlayer.setOnPreparedListener(mp -> {
                int dur = mp.getDuration();
                if (dur > 10000) mp.seekTo(dur / 10);
                mp.start();
            });
            previewPlayer.prepareAsync();
        } catch (Exception e) {
            stopPreview();
        }
    }

    private void releasePlayer() {
        if (previewPlayer != null) {
            try { previewPlayer.stop(); previewPlayer.release(); } catch (Exception ignored) {}
            previewPlayer = null;
        }
    }

    public void stopPreview() {
        if (stopRunnable != null) { handler.removeCallbacks(stopRunnable); stopRunnable = null; }
        releasePlayer();
        if (previewDialog != null) {
            try { if (previewDialog.isShowing()) previewDialog.dismiss(); } catch (Exception ignored) {}
            previewDialog = null;
        }
    }

    @Override public void onViewRecycled(@NonNull VideoViewHolder holder) {
        super.onViewRecycled(holder);
        Glide.with(holder.itemView.getContext()).clear(holder.ivThumbnail);
    }

    @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        stopPreview();
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
