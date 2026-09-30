package com.vlcplayer.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MangaActivity extends AppCompatActivity {
    private static final String STATE_PAGE = "manga_page";
    private static final String STATE_BARS = "manga_bars_visible";

    private ViewPager2 viewPager;
    private TextView tvTitle, tvPage, stateTitle, stateMessage;
    private SeekBar seekBar;
    private ProgressBar progress;
    private View topBar, bottomBar, readerState;
    private boolean barsVisible = true;
    private boolean pagesLoaded;
    private boolean destroyed;
    private int restoredPage;
    private Uri sourceUri;
    private LoadSession currentSession;
    private ViewPager2.OnPageChangeCallback pageCallback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService cancellationExecutor = Executors.newCachedThreadPool();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manga);
        viewPager = findViewById(R.id.viewPager);
        tvTitle = findViewById(R.id.tv_title);
        tvPage = findViewById(R.id.tv_page);
        seekBar = findViewById(R.id.seekBar);
        progress = findViewById(R.id.progress);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        readerState = findViewById(R.id.manga_reader_state);
        stateTitle = findViewById(R.id.manga_state_title);
        stateMessage = findViewById(R.id.manga_state_message);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.manga_retry).setOnClickListener(v -> loadManga());
        findViewById(R.id.manga_close).setOnClickListener(v -> finish());

        if (getIntent().getData() != null) {
            sourceUri = getIntent().getData();
        } else if (getIntent().getStringExtra("uri") != null) {
            sourceUri = Uri.parse(getIntent().getStringExtra("uri"));
        }
        if (sourceUri == null) {
            finish();
            return;
        }
        String name = sourceUri.getLastPathSegment();
        if (name != null) tvTitle.setText(name);
        if (savedInstanceState != null) {
            restoredPage = Math.max(0, savedInstanceState.getInt(STATE_PAGE, 0));
            barsVisible = savedInstanceState.getBoolean(STATE_BARS, true);
        }
        viewPager.setOnClickListener(v -> toggleBars());
        loadManga();
    }

    private void toggleBars() {
        if (!pagesLoaded) return;
        barsVisible = !barsVisible;
        updateBars();
    }

    private void updateBars() {
        topBar.setVisibility(!pagesLoaded || barsVisible ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(pagesLoaded && barsVisible ? View.VISIBLE : View.GONE);
    }

    private void loadManga() {
        if (destroyed || isFinishing()) return;
        detachPager();
        discardSession();
        pagesLoaded = false;
        updateBars();
        readerState.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        tvPage.setText("");

        File cacheRoot = new File(getCacheDir(), "manga_pages");
        LoadSession session = new LoadSession(new File(cacheRoot, UUID.randomUUID().toString()));
        currentSession = session;
        session.future = executor.submit(() -> {
            List<File> pages = null;
            Exception failure = null;
            try (AssetFileDescriptor descriptor = getContentResolver()
                    .openAssetFileDescriptor(sourceUri, "r", session.cancellation)) {
                if (descriptor == null) throw new IOException("Cannot open CBZ");
                session.registerDescriptor(descriptor);
                try (InputStream stream = descriptor.createInputStream()) {
                    session.registerStream(stream);
                    pages = CbzArchiveReader.read(stream, session.directory,
                            () -> session.cancelled);
                }
            } catch (Exception error) {
                failure = error;
            } finally {
                // Readers and ZIP streams have closed before a discarded cache is deleted.
                session.workerFinished();
            }
            final List<File> result = pages;
            final Exception error = failure;
            handler.post(() -> {
                if (destroyed || isFinishing() || currentSession != session || session.cancelled) return;
                progress.setVisibility(View.GONE);
                if (error != null) {
                    Log.w("MangaActivity", "Cannot read CBZ", error);
                    showLoadFailure(false);
                    discardSession();
                } else if (result == null || result.isEmpty()) {
                    showLoadFailure(true);
                    discardSession();
                } else {
                    setupPager(result);
                }
            });
        });
    }

    private void showLoadFailure(boolean empty) {
        pagesLoaded = false;
        updateBars();
        stateTitle.setText(empty ? R.string.manga_empty_title : R.string.manga_load_error_title);
        stateMessage.setText(empty ? R.string.manga_empty_message : R.string.manga_load_error_message);
        readerState.setVisibility(View.VISIBLE);
    }

    private void setupPager(List<File> pages) {
        pagesLoaded = true;
        viewPager.setAdapter(new MangaPageAdapter(pages));
        viewPager.setOffscreenPageLimit(2);
        seekBar.setMax(pages.size() - 1);
        pageCallback = new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                restoredPage = position;
                tvPage.setText(getString(R.string.manga_page_counter, position + 1, pages.size()));
                seekBar.setProgress(position);
            }
        };
        viewPager.registerOnPageChangeCallback(pageCallback);
        int startPage = Math.min(restoredPage, pages.size() - 1);
        restoredPage = startPage;
        viewPager.setCurrentItem(startPage, false);
        tvPage.setText(getString(R.string.manga_page_counter, startPage + 1, pages.size()));
        seekBar.setProgress(startPage);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int position, boolean user) {
                if (user) viewPager.setCurrentItem(position, false);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        updateBars();
    }

    private void detachPager() {
        if (pageCallback != null) {
            viewPager.unregisterOnPageChangeCallback(pageCallback);
            pageCallback = null;
        }
        seekBar.setOnSeekBarChangeListener(null);
        viewPager.setAdapter(null);
    }

    private void discardSession() {
        LoadSession session = currentSession;
        currentSession = null;
        if (session != null) session.cancel(executor, cancellationExecutor);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt(STATE_PAGE, restoredPage);
        state.putBoolean(STATE_BARS, barsVisible);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        detachPager();
        discardSession();
        // Let queued cache deletion complete after the cancelled reader releases its streams.
        executor.shutdown();
        cancellationExecutor.shutdown();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static final class LoadSession {
        final File directory;
        final CancellationSignal cancellation = new CancellationSignal();
        volatile boolean cancelled;
        Future<?> future;
        private boolean finished;
        private boolean cleanupRequested;
        private InputStream activeStream;
        private AssetFileDescriptor activeDescriptor;

        LoadSession(File directory) {
            this.directory = directory;
        }

        void registerDescriptor(AssetFileDescriptor descriptor) throws IOException {
            synchronized (this) {
                if (!cancelled) {
                    activeDescriptor = descriptor;
                    return;
                }
            }
            descriptor.close();
            throw new InterruptedIOException("CBZ loading cancelled");
        }

        void registerStream(InputStream stream) throws IOException {
            synchronized (this) {
                if (!cancelled) {
                    activeStream = stream;
                    return;
                }
            }
            stream.close();
            throw new InterruptedIOException("CBZ loading cancelled");
        }

        void cancel(ExecutorService cleanupExecutor, ExecutorService closeExecutor) {
            boolean cleanup;
            InputStream stream;
            AssetFileDescriptor descriptor;
            synchronized (this) {
                cancelled = true;
                cleanup = finished && !cleanupRequested;
                if (cleanup) cleanupRequested = true;
                stream = activeStream;
                descriptor = activeDescriptor;
            }
            cancellation.cancel();
            if (future != null) future.cancel(true);
            // Interrupt alone does not unblock every provider pipe; close its handles off the UI thread.
            if (stream != null || descriptor != null) {
                closeExecutor.execute(() -> {
                    if (descriptor != null) {
                        try { descriptor.close(); } catch (IOException ignored) {}
                    }
                    if (stream != null) {
                        try { stream.close(); } catch (IOException ignored) {}
                    }
                });
            }
            if (cleanup) cleanupExecutor.execute(() -> CbzArchiveReader.deleteSession(directory));
        }

        void workerFinished() {
            boolean cleanup;
            synchronized (this) {
                finished = true;
                activeStream = null;
                activeDescriptor = null;
                cleanup = cancelled && !cleanupRequested;
                if (cleanup) cleanupRequested = true;
            }
            if (cleanup) CbzArchiveReader.deleteSession(directory);
        }
    }

    private class MangaPageAdapter extends RecyclerView.Adapter<MangaPageAdapter.PageVH> {
        private final List<File> pages;

        MangaPageAdapter(List<File> pages) {
            this.pages = pages;
        }

        @Override public PageVH onCreateViewHolder(ViewGroup parent, int type) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_manga_page, parent, false);
            return new PageVH(view);
        }

        @Override public void onBindViewHolder(PageVH holder, int position) {
            holder.bind(pages.get(position), position);
        }

        @Override public int getItemCount() { return pages.size(); }

        @Override public void onViewRecycled(PageVH holder) {
            Glide.with(holder.image).clear(holder.image);
            super.onViewRecycled(holder);
        }

        class PageVH extends RecyclerView.ViewHolder {
            final ImageView image;
            final ProgressBar pageProgress;
            final View pageError;
            File boundPage;

            PageVH(View view) {
                super(view);
                image = view.findViewById(R.id.imageView);
                pageProgress = view.findViewById(R.id.page_progress);
                pageError = view.findViewById(R.id.manga_page_error);
                view.setOnClickListener(v -> toggleBars());
                pageError.setOnClickListener(v -> {
                    if (boundPage != null) loadPage(boundPage);
                });
            }

            void bind(File page, int position) {
                boundPage = page;
                image.setContentDescription(getString(R.string.manga_page_description, position + 1));
                loadPage(page);
            }

            void loadPage(File page) {
                Glide.with(image).clear(image);
                pageProgress.setVisibility(View.VISIBLE);
                pageError.setVisibility(View.GONE);
                Glide.with(image).load(page).fitCenter()
                        .listener(new RequestListener<Drawable>() {
                            @Override public boolean onLoadFailed(@Nullable GlideException error,
                                    Object model, Target<Drawable> target, boolean first) {
                                pageProgress.setVisibility(View.GONE);
                                pageError.setVisibility(View.VISIBLE);
                                return false;
                            }

                            @Override public boolean onResourceReady(Drawable resource,
                                    Object model, Target<Drawable> target,
                                    DataSource source, boolean first) {
                                pageProgress.setVisibility(View.GONE);
                                pageError.setVisibility(View.GONE);
                                return false;
                            }
                        }).into(image);
            }
        }
    }
}
