package com.vlcplayer.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.VideoDecoder;
import com.bumptech.glide.request.RequestOptions;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;
import java.util.List;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(VideoItem video);
    }

    private final List<VideoItem> videoList;
    private final OnVideoClickListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Chỉ 1 preview chạy tại 1 thời điểm
    private VideoViewHolder activePreviewHolder = null;
    private LibVLC previewLibVLC = null;
    private MediaPlayer previewPlayer = null;
    private Runnable stopPreviewRunnable = null;

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

        // Load thumbnail bằng Glide
        holder.ivThumbnail.clearColorFilter();
        holder.ivThumbnail.setImageTintList(null);
        Glide.with(holder.itemView.getContext())
            .asBitmap()
            .load(video.getUri())
            .apply(RequestOptions.frameOf(3_000_000L).centerCrop()
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play))
            .into(holder.ivThumbnail);

        // Tap → mở player
        holder.itemView.setOnClickListener(v -> {
            stopPreview();
            listener.onVideoClick(video);
        });

        // Long press → bắt đầu preview
        holder.itemView.setOnLongClickListener(v -> {
            startPreview(holder, video, holder.itemView.getContext());
            return true;
        });
    }

    private void startPreview(VideoViewHolder holder, VideoItem video, Context ctx) {
        // Dừng preview cũ nếu có
        stopPreview();

        activePreviewHolder = holder;

        // Hiện SurfaceView, ẩn thumbnail
        holder.surfacePreview.setVisibility(View.VISIBLE);
        holder.ivPreviewIcon.setVisibility(View.VISIBLE);
        holder.ivThumbnail.setVisibility(View.INVISIBLE);

        holder.surfacePreview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder sh) {
                playPreview(ctx, video.getUri(), sh);
            }
            @Override public void surfaceChanged(@NonNull SurfaceHolder sh, int f, int w, int h) {}
            @Override public void surfaceDestroyed(@NonNull SurfaceHolder sh) {}
        });

        // Tự dừng sau 8 giây
        stopPreviewRunnable = () -> stopPreview();
        handler.postDelayed(stopPreviewRunnable, 8000);
    }

    private void playPreview(Context ctx, Uri uri, SurfaceHolder holder) {
        try {
            ArrayList<String> options = new ArrayList<>();
            options.add("--no-audio");
            options.add("--clock-jitter=0");
            options.add("--clock-synchro=0");

            previewLibVLC = new LibVLC(ctx, options);
            previewPlayer = new MediaPlayer(previewLibVLC);
            previewPlayer.getVLCVout().setVideoSurface(holder.getSurface(), holder);
            previewPlayer.getVLCVout().attachViews();

            Media media = new Media(previewLibVLC, uri);
            media.setHWDecoderEnabled(true, true);
            // Seek đến 10% thời lượng để tránh màn đen
            previewPlayer.setMedia(media);
            media.release();

            previewPlayer.setEventListener(event -> {
                if (event.type == MediaPlayer.Event.Playing) {
                    // Seek đến 10% sau khi bắt đầu phát
                    long len = previewPlayer.getLength();
                    if (len > 0) {
                        previewPlayer.setTime(len / 10);
                    }
                    // Ẩn icon play khi đang phát
                    handler.post(() -> {
                        if (activePreviewHolder != null) {
                            activePreviewHolder.ivPreviewIcon.setVisibility(View.GONE);
                        }
                    });
                }
            });

            previewPlayer.play();
        } catch (Exception e) {
            stopPreview();
        }
    }

    private void stopPreview() {
        if (stopPreviewRunnable != null) {
            handler.removeCallbacks(stopPreviewRunnable);
            stopPreviewRunnable = null;
        }
        if (previewPlayer != null) {
            try {
                previewPlayer.stop();
                previewPlayer.getVLCVout().detachViews();
                previewPlayer.release();
            } catch (Exception ignored) {}
            previewPlayer = null;
        }
        if (previewLibVLC != null) {
            try { previewLibVLC.release(); } catch (Exception ignored) {}
            previewLibVLC = null;
        }
        if (activePreviewHolder != null) {
            final VideoViewHolder h = activePreviewHolder;
            handler.post(() -> {
                h.surfacePreview.setVisibility(View.GONE);
                h.ivPreviewIcon.setVisibility(View.GONE);
                h.ivThumbnail.setVisibility(View.VISIBLE);
            });
            activePreviewHolder = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull VideoViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder == activePreviewHolder) stopPreview();
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
        ImageView ivThumbnail, ivPreviewIcon;
        SurfaceView surfacePreview;

        VideoViewHolder(@NonNull View v) {
            super(v);
            tvName        = v.findViewById(R.id.tv_video_name);
            tvDuration    = v.findViewById(R.id.tv_duration);
            tvSize        = v.findViewById(R.id.tv_size);
            ivThumbnail   = v.findViewById(R.id.iv_thumbnail);
            surfacePreview = v.findViewById(R.id.surface_preview);
            ivPreviewIcon  = v.findViewById(R.id.iv_preview_icon);
        }
    }
}
