package com.vlcplayer.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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

import java.util.List;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(VideoItem video);
    }

    private final List<VideoItem> videoList;
    private final OnVideoClickListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

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

        holder.ivThumbnail.clearColorFilter();
        holder.ivThumbnail.setImageTintList(null);
        Glide.with(holder.itemView.getContext())
            .asBitmap()
            .load(video.getUri())
            .apply(RequestOptions.frameOf(3_000_000L).centerCrop()
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play))
            .into(holder.ivThumbnail);

        holder.itemView.setOnClickListener(v -> {
            stopPreview();
            listener.onVideoClick(video);
        });

        holder.itemView.setOnLongClickListener(v -> {
            showPreview(holder.itemView.getContext(), video);
            return true;
        });
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

        // TextureView callback — Surface sẵn sàng mới play
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                Surface surface = new Surface(st);
                startMediaPlayer(ctx, video.getUri(), surface, textureView);
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                releasePlayer();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });

        // Tự đóng sau 10 giây
        stopRunnable = this::stopPreview;
        handler.postDelayed(stopRunnable, 10000);
    }

    private void startMediaPlayer(Context ctx, Uri uri, Surface surface, TextureView tv) {
        try {
            previewPlayer = new MediaPlayer();
            previewPlayer.setDataSource(ctx, uri);
            previewPlayer.setSurface(surface);
            previewPlayer.setVolume(0, 0); // tắt âm thanh
            previewPlayer.setOnVideoSizeChangedListener((mp, width, height) -> {
                // Điều chỉnh tỉ lệ TextureView
                if (width > 0 && height > 0 && tv.getWidth() > 0) {
                    float scaleX = 1f;
                    float scaleY = (float)(tv.getWidth() * height) / (float)(width * tv.getHeight());
                    if (scaleY < 1f) {
                        scaleX = 1f / scaleY;
                        scaleY = 1f;
                    }
                    tv.setScaleX(scaleX);
                    tv.setScaleY(scaleY);
                }
            });
            previewPlayer.setOnPreparedListener(mp -> {
                // Seek đến 10% thời lượng
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
            try {
                previewPlayer.stop();
                previewPlayer.release();
            } catch (Exception ignored) {}
            previewPlayer = null;
        }
    }

    public void stopPreview() {
        if (stopRunnable != null) {
            handler.removeCallbacks(stopRunnable);
            stopRunnable = null;
        }
        releasePlayer();
        if (previewDialog != null) {
            try {
                if (previewDialog.isShowing()) previewDialog.dismiss();
            } catch (Exception ignored) {}
            previewDialog = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull VideoViewHolder holder) {
        super.onViewRecycled(holder);
        Glide.with(holder.itemView.getContext()).clear(holder.ivThumbnail);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
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
