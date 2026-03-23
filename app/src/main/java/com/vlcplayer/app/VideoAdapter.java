package com.vlcplayer.app;

import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
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

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(VideoItem video);
    }

    private final List<VideoItem> videoList;
    private final OnVideoClickListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Dialog previewDialog = null;
    private LibVLC previewLibVLC = null;
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

        // Tạo dialog
        previewDialog = new Dialog(ctx);
        previewDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Inflate layout chứa VLCVideoLayout
        View dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_preview, null);
        previewDialog.setContentView(dialogView);

        // Set kích thước dialog
        Window window = previewDialog.getWindow();
        if (window != null) {
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            window.setLayout(screenW, screenH / 2);
            window.setBackgroundDrawableResource(android.R.color.black);
            // Vị trí giữa màn hình
            WindowManager.LayoutParams params = window.getAttributes();
            params.y = -screenH / 6;
            window.setAttributes(params);
        }

        // Lấy VLCVideoLayout từ layout
        VLCVideoLayout videoLayout = dialogView.findViewById(R.id.vlc_preview);

        // Đóng khi bấm vào
        dialogView.setOnClickListener(v -> stopPreview());
        previewDialog.setOnDismissListener(d -> stopPreview());
        previewDialog.show();

        // Khởi tạo VLC và play ngay khi dialog đã hiện
        // Quan trọng: attachViews PHẢI gọi sau khi View đã được attach vào window
        videoLayout.post(() -> startVLC(ctx, video.getUri(), videoLayout));

        // Tự đóng sau 10 giây
        stopRunnable = this::stopPreview;
        handler.postDelayed(stopRunnable, 10000);
    }

    private void startVLC(Context ctx, Uri uri, VLCVideoLayout layout) {
        try {
            ArrayList<String> opts = new ArrayList<>();
            opts.add("--no-audio");
            opts.add("--clock-jitter=0");
            opts.add("--clock-synchro=0");
            opts.add("--avcodec-threads=0");

            previewLibVLC = new LibVLC(ctx, opts);
            previewPlayer = new MediaPlayer(previewLibVLC);

            // attachViews TRƯỚC setMedia và play
            previewPlayer.attachViews(layout, null, false, false);

            Media media = new Media(previewLibVLC, uri);
            media.setHWDecoderEnabled(true, true);
            previewPlayer.setMedia(media);
            media.release();

            previewPlayer.setEventListener(event -> {
                if (event.type == MediaPlayer.Event.Playing) {
                    // Seek đến 10% để tránh màn đen đầu video
                    long len = previewPlayer.getLength();
                    if (len > 10000) {
                        previewPlayer.setTime(len / 10);
                    }
                    previewPlayer.setAspectRatio(null);
                    previewPlayer.setScale(0);
                }
            });

            previewPlayer.play();

        } catch (Exception e) {
            stopPreview();
        }
    }

    public void stopPreview() {
        if (stopRunnable != null) {
            handler.removeCallbacks(stopRunnable);
            stopRunnable = null;
        }
        if (previewPlayer != null) {
            try {
                previewPlayer.stop();
                previewPlayer.detachViews();
                previewPlayer.release();
            } catch (Exception ignored) {}
            previewPlayer = null;
        }
        if (previewLibVLC != null) {
            try { previewLibVLC.release(); } catch (Exception ignored) {}
            previewLibVLC = null;
        }
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
