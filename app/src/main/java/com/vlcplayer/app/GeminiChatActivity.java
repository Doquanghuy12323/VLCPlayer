package com.vlcplayer.app;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class GeminiChatActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private EditText etInput;
    private ImageButton btnSend;
    private ProgressBar progress;
    private ChatAdapter adapter;
    private GeminiHelper gemini;
    private List<String[]> messages = new ArrayList<>(); // [role, text]
    private StringBuilder history = new StringBuilder();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gemini_chat);

        recyclerView = findViewById(R.id.recyclerView);
        etInput      = findViewById(R.id.et_input);
        btnSend      = findViewById(R.id.btn_send);
        progress     = findViewById(R.id.progress);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        gemini = new GeminiHelper();
        adapter = new ChatAdapter(messages);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // Nhan video title tu intent
        String videoTitle = getIntent().getStringExtra("video_title");
        if (videoTitle != null && !videoTitle.isEmpty()) {
            addMessage("system", "Nguoi dung dang xem video: " + videoTitle);
            addMessage("bot", "Xin chao! Toi co the giup gi cho ban ve video \"" + videoTitle + "\"?");
        } else {
            addMessage("bot", "Xin chao! Toi la tro ly AI. Ban can giup gi?");
        }

        btnSend.setOnClickListener(v -> sendMessage());
        etInput.setOnEditorActionListener((v, a, e) -> { sendMessage(); return true; });
    }

    private void sendMessage() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;

        etInput.setText("");
        addMessage("user", text);
        progress.setVisibility(View.VISIBLE);
        btnSend.setEnabled(false);

        // Giu context hoi thoai
        history.append("User: ").append(text).append("\n");
        // Chi giu 1500 ky tu cuoi de tiet kiem token
        String histStr = history.toString();
        if (histStr.length() > 1500) histStr = histStr.substring(histStr.length() - 1500);
        String prompt = histStr + "Assistant:";

        gemini.ask(prompt, new GeminiHelper.Callback() {
            @Override public void onResult(String result) {
                history.append(result).append("\n");
                progress.setVisibility(View.GONE);
                btnSend.setEnabled(true);
                addMessage("bot", result);
            }
            @Override public void onError(String error) {
                progress.setVisibility(View.GONE);
                btnSend.setEnabled(true);
                addMessage("bot", "Loi: " + error);
            }
        });
    }

    private void addMessage(String role, String text) {
        messages.add(new String[]{role, text});
        adapter.notifyItemInserted(messages.size() - 1);
        recyclerView.scrollToPosition(messages.size() - 1);
    }

    class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        private List<String[]> data;
        ChatAdapter(List<String[]> data) { this.data = data; }

        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_chat_message, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            String[] msg = data.get(pos);
            h.bind(msg[0], msg[1]);
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvUser, tvBot;
            VH(View v) {
                super(v);
                tvUser = v.findViewById(R.id.tv_user);
                tvBot  = v.findViewById(R.id.tv_bot);
            }
            void bind(String role, String text) {
                if ("user".equals(role)) {
                    tvUser.setVisibility(View.VISIBLE);
                    tvBot.setVisibility(View.GONE);
                    tvUser.setText(text);
                } else if ("system".equals(role)) {
                    tvUser.setVisibility(View.GONE);
                    tvBot.setVisibility(View.GONE);
                } else {
                    tvBot.setVisibility(View.VISIBLE);
                    tvUser.setVisibility(View.GONE);
                    tvBot.setText(text);
                }
            }
        }
    }
}
