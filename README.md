# 🎬 VLC Video Player for Android

Ứng dụng phát video Android sử dụng thư viện **libVLC**, phát triển bằng **Termux + Acode** và build tự động qua **GitHub Actions**.

## ✨ Tính năng
- 📂 Tự động quét & liệt kê video trên thiết bị
- ▶️ Phát video full-screen với libVLC (H.264, H.265, VP9, MKV, MP4, AVI...)
- ⏩ Tua nhanh / lùi 10 giây
- 🔆 Ẩn/hiện controls khi chạm màn hình
- 📁 Chọn file video thủ công qua file picker
- 🧲 Stream torrent với chọn file và HTTP Range
- 📚 Đọc truyện CBZ/ZIP và duyệt truyện online
- 🤖 Trợ lý Gemini bằng API key lưu riêng trên thiết bị
- 📡 Phát file video qua mạng LAN cho VLC trên máy tính
- 🔐 Chế độ riêng tư chống chụp/quay màn hình
- 🌙 Giao diện dark theme

## 🚀 Build từ Termux

```bash
# 1. Clone về
git clone https://github.com/Doquanghuy12323/VLCPlayer.git
cd VLCPlayer

# 2. Build APK (cần Java 17 trong Termux)
pkg install openjdk-17
./gradlew assembleDebug

# APK tại: app/build/outputs/apk/debug/app-debug.apk
```

## ⚙️ GitHub Actions
Push code lên GitHub → Actions tự động build APK → tải từ mục **Releases**.

Workflow sẽ chạy Android Lint, tạo `versionCode` tự động, ký APK bằng GitHub
Secrets và tạo GitHub Release. Các secret cần có: `KEYSTORE_BASE64`, `KEY_ALIAS`,
`KEY_PASSWORD`, `STORE_PASSWORD`.

Gemini API key không còn được nhúng trong APK. Mở mục Trợ lý AI trong ứng dụng
và nhập key của bạn khi được hỏi.

## 📦 Dependencies
- `org.videolan.android:libvlc-all:3.6.0`
- AndroidX AppCompat, RecyclerView, Material Design
