package com.vlcplayer.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MangaActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private TextView tvTitle, tvPage;
    private SeekBar seekBar;
    private ProgressBar progress;
    private View topBar, bottomBar;
    private boolean barsVisible = true;

    private List<Object> pages = new ArrayList<>(); // Uri hoac ZipEntry name
    private Uri sourceUri;
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
            List<String> entryNames = new ArrayList<>();
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                ZipInputStream zis = new ZipInputStream(is);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String n = entry.getName().toLowerCase();
                    if (n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                        n.endsWith(".png") || n.endsWith(".webp") ||
                        n.endsWith(".gif")) {
                        entryNames.add(entry.getName());
                    }
                }
                zis.close();
                Collections.sort(entryNames);
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this,
                    "Loi: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }

            final List<String> finalNames = entryNames;
            handler.post(() -> {
                progress.setVisibility(View.GONE);
                if (finalNames.isEmpty()) {
                    Toast.makeText(this, "Khong tim thay trang nao", Toast.LENGTH_SHORT).show();
                    return;
                }
                setupPager(finalNames);
            });
        });
    }

    private void setupPager(List<String> names) {
        MangaPageAdapter adapter = new MangaPageAdapter(names, sourceUri);
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(2);

        seekBar.setMax(names.size() - 1);
        tvPage.setText("1 / " + names.size());

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int pos) {
                tvPage.setText((pos + 1) + " / " + names.size());
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
        executor.shutdown();
    }

    // Adapter
    class MangaPageAdapter extends RecyclerView.Adapter<MangaPageAdapter.PageVH> {
        private final List<String> names;
        private final Uri zipUri;

        MangaPageAdapter(List<String> names, Uri zipUri) {
            this.names = names;
            this.zipUri = zipUri;
        }

        @Override public PageVH onCreateViewHolder(ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_manga_page, parent, false);
            return new PageVH(v);
        }

        @Override public void onBindViewHolder(PageVH holder, int pos) {
            holder.bind(names.get(pos));
        }

        @Override public int getItemCount() { return names.size(); }

        class PageVH extends RecyclerView.ViewHolder {
            ImageView img;
            ProgressBar pb;

            PageVH(View v) {
                super(v);
                img = v.findViewById(R.id.imageView);
                pb  = v.findViewById(R.id.page_progress);
                v.setOnClickListener(x -> toggleBars());
            }

            void bind(String entryName) {
                img.setImageBitmap(null);
                pb.setVisibility(View.VISIBLE);
                executor.execute(() -> {
                    Bitmap bmp = null;
                    try {
                        InputStream is = getContentResolver().openInputStream(zipUri);
                        ZipInputStream zis = new ZipInputStream(is);
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.getName().equals(entryName)) {
                                bmp = BitmapFactory.decodeStream(zis);
                                break;
                            }
                        }
                        zis.close();
                    } catch (Exception ignored) {}
                    final Bitmap finalBmp = bmp;
                    handler.post(() -> {
                        pb.setVisibility(View.GONE);
                        if (finalBmp != null) img.setImageBitmap(finalBmp);
                    });
                });
            }
        }
    }
}
