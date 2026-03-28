# 🎬 VLC Video Player for Android

Ứng dụng phát video Android sử dụng thư viện **libVLC**, phát triển bằng **Termux + Acode** và build tự động qua **GitHub Actions**.

## ✨ Tính năng
- 📂 Tự động quét & liệt kê video trên thiết bị
- ▶️ Phát video full-screen với libVLC (H.264, H.265, VP9, MKV, MP4, AVI...)
- ⏩ Tua nhanh / lùi 10 giây
- 🔆 Ẩn/hiện controls khi chạm màn hình
- 📁 Chọn file video thủ công qua file picker
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
Push code lên GitHub → Actions tự động build APK → Download từ tab **Artifacts**.

## 📦 Dependencies
- `org.videolan.android:libvlc-all:3.6.0`
- AndroidX AppCompat, RecyclerView, Material Design
