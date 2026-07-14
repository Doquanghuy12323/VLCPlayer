package com.vlcplayer.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import android.graphics.drawable.Drawable;
import androidx.annotation.Nullable;

public class MangaActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private TextView tvTitle, tvPage;
    private SeekBar seekBar;
    private ProgressBar progress;
    private View topBar, bottomBar;
    private boolean barsVisible = true;

    private Uri sourceUri;
    private File extractionDir;
    private ExecutorService executor = Executors.newFixedThreadPool(2);
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manga);

        viewPager = findViewById(R.id.viewPager);
        tvTitle   = findViewById(R.id.tv_title);
        tvPage    = findViewById(R.id.tv_page);
        seekBar   = findViewById(R.id.seekBar);
        progress  = findViewById(R.id.progress);
        topBar    = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // Nhan URI tu intent
        if (getIntent().getData() != null) {
            sourceUri = getIntent().getData();
        } else if (getIntent().getStringExtra("uri") != null) {
            sourceUri = Uri.parse(getIntent().getStringExtra("uri"));
        }

        if (sourceUri == null) { finish(); return; }

        String name = sourceUri.getLastPathSegment();
        if (name != null) tvTitle.setText(name);

        // Toggle bars khi tap
        viewPager.setOnClickListener(v -> toggleBars());

        loadManga(sourceUri);
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        int vis = barsVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(vis);
        bottomBar.setVisibility(vis);
    }

    private void loadManga(Uri uri) {
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<File> pageFiles = new ArrayList<>();
            try {
                extractionDir = new File(getCacheDir(), "manga_pages");
                deleteDir(extractionDir);
                if (!extractionDir.mkdirs() && !extractionDir.isDirectory()) {
                    throw new java.io.IOException("Khong tao duoc bo nho tam");
                }
                String rootPath = extractionDir.getCanonicalPath() + File.separator;
                long totalBytes = 0;
                int entryCount = 0;
                try (InputStream is = getContentResolver().openInputStream(uri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    if (is == null) throw new java.io.IOException("Khong mo duoc file CBZ");
                    ZipEntry entry;
                    byte[] buffer = new byte[16384];
                    while ((entry = zis.getNextEntry()) != null) {
                        String n = entry.getName().toLowerCase(java.util.Locale.US);
                        boolean image = n.endsWith(".jpg") || n.endsWith(".jpeg")
                            || n.endsWith(".png") || n.endsWith(".webp")
                            || n.endsWith(".gif");
                        if (entry.isDirectory() || !image) continue;
                        if (++entryCount > 2000) throw new java.io.IOException("CBZ co qua nhieu trang");

                        File output = new File(extractionDir, entry.getName());
                        if (!output.getCanonicalPath().startsWith(rootPath)) {
                            throw new java.io.IOException("Duong dan khong hop le trong CBZ");
                        }
                        File parent = output.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(output)) {
                            int read;
                            while ((read = zis.read(buffer)) != -1) {
                                totalBytes += read;
                                if (totalBytes > 500L * 1024 * 1024) {
                                    throw new java.io.IOException("CBZ vuot gioi han 500 MB");
                                }
                                fos.write(buffer, 0, read);
                            }
                        }
                        pageFiles.add(output);
                    }
                }
                Collections.sort(pageFiles,
                    (a, b) -> naturalCompare(a.getName(), b.getName()));
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this,
                    "Loi: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }

            final List<File> finalPages = pageFiles;
            handler.post(() -> {
                progress.setVisibility(View.GONE);
                if (finalPages.isEmpty()) {
                    Toast.makeText(this, "Khong tim thay trang nao", Toast.LENGTH_SHORT).show();
                    return;
                }
                setupPager(finalPages);
            });
        });
    }

    private void setupPager(List<File> pages) {
        MangaPageAdapter adapter = new MangaPageAdapter(pages);
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(2);

        seekBar.setMax(pages.size() - 1);
        tvPage.setText("1 / " + pages.size());

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int pos) {
                tvPage.setText((pos + 1) + " / " + pages.size());
                seekBar.setProgress(pos);
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (user) viewPager.setCurrentItem(p, false);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // Adapter
    class MangaPageAdapter extends RecyclerView.Adapter<MangaPageAdapter.PageVH> {
        private final List<File> pages;

        MangaPageAdapter(List<File> pages) {
            this.pages = pages;
        }

        @Override public PageVH onCreateViewHolder(ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_manga_page, parent, false);
            return new PageVH(v);
        }

        @Override public void onBindViewHolder(PageVH holder, int pos) {
            holder.bind(pages.get(pos));
        }

        @Override public int getItemCount() { return pages.size(); }

        @Override public void onViewRecycled(PageVH holder) {
            Glide.with(holder.img).clear(holder.img);
            super.onViewRecycled(holder);
        }

        class PageVH extends RecyclerView.ViewHolder {
            ImageView img;
            ProgressBar pb;

            PageVH(View v) {
                super(v);
                img = v.findViewById(R.id.imageView);
                pb  = v.findViewById(R.id.page_progress);
                v.setOnClickListener(x -> toggleBars());
            }

            void bind(File page) {
                Glide.with(img).clear(img);
                pb.setVisibility(View.VISIBLE);
                Glide.with(img)
                    .load(page)
                    .fitCenter()
                    .listener(new RequestListener<Drawable>() {
                        @Override public boolean onLoadFailed(@Nullable GlideException e,
                                Object model, Target<Drawable> target, boolean first) {
                            pb.setVisibility(View.GONE);
                            return false;
                        }
                        @Override public boolean onResourceReady(Drawable resource,
                                Object model, Target<Drawable> target,
                                DataSource source, boolean first) {
                        pb.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .into(img);
            }
        }
    }

    private int naturalCompare(String a, String b) {
        int ia = 0, ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia), cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                long na = 0, nb = 0;
                while (ia < a.length() && Character.isDigit(a.charAt(ia)))
                    na = Math.min(Integer.MAX_VALUE, na * 10 + a.charAt(ia++) - '0');
                while (ib < b.length() && Character.isDigit(b.charAt(ib)))
                    nb = Math.min(Integer.MAX_VALUE, nb * 10 + b.charAt(ib++) - '0');
                if (na != nb) return Long.compare(na, nb);
            } else {
                ca = Character.toLowerCase(ca);
                cb = Character.toLowerCase(cb);
                if (ca != cb) return Character.compare(ca, cb);
                ia++; ib++;
            }
        }
        return Integer.compare(a.length(), b.length());
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) {
            if (file.isDirectory()) deleteDir(file); else file.delete();
        }
        dir.delete();
    }
}
