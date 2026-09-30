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
Mỗi pull request và lần push lên `main` đều chạy Android Lint, unit test và build
APK debug. Tải bản cài thử trong artifact `vlcplayer-debug` của lần chạy Actions.
Để phát hành APK đã ký, mở **Actions → Verify and release VLC Player
→ Run workflow** trên nhánh `main`. Workflow tạo `versionCode` tự động và đưa APK
vào **Releases** sau khi các bước kiểm tra thành công.

Các secret cần cho bước phát hành: `KEYSTORE_BASE64`, `KEY_ALIAS`,
`KEY_PASSWORD`, `STORE_PASSWORD`. Pull request không sử dụng các secret này.

## Quyền truy cập thư viện video
Trên Android 14 trở lên, bạn có thể cho phép ứng dụng đọc toàn bộ video hoặc chỉ
một số video đã chọn. Khi chỉ cấp quyền một phần, thư viện hiển thị thông báo và
nút **Chọn thêm video** để mở lại trình chọn của Android. Danh sách được cập nhật
khi quay về ứng dụng để phản ánh quyền và các video còn truy cập được.

Nếu từ chối quyền thư viện, mục **Mở → Video trên máy** vẫn cho phép mở từng file.

## Lưu dữ liệu torrent
Trong màn Torrent, **Tự xóa dữ liệu khi đóng video** được ghi nhớ cho các phiên mới.
Bật để dừng tải và dọn dữ liệu tạm khi đóng video; tắt để dừng tải nhưng giữ dữ liệu
trong thư mục riêng của ứng dụng. Dữ liệu được giữ không bị xóa khi mở torrent khác
hoặc dọn cache. Bạn vẫn có thể xóa từng file trong danh sách.

Torrent tải theo phần video đang đọc, nên file giữ lại có thể chưa hoàn tất.
Chạm dữ liệu đã giữ để mở lại torrent gốc và tiếp tục tải qua proxy; cần mạng và
nguồn torrent còn hoạt động. File chưa tải đủ không bảo đảm phát được ngoại tuyến.

## Phát video qua LAN
Mở **Công cụ → Phát video qua LAN**, chọn video, rồi nhấn **Bắt đầu chia sẻ**.
Chọn file chỉ chuẩn bị thông tin video. Khi chia sẻ hoạt động, nhấn **Sao chép
liên kết** và mở địa chỉ bằng VLC trên thiết bị cùng mạng LAN hoặc Wi-Fi.

Phiên chia sẻ và liên kết được giữ khi xoay màn hình. Nhấn **Dừng** để kết thúc
phiên; khôi phục màn hình đã dừng không tự chia sẻ lại. Nếu tiến trình ứng dụng
đã kết thúc, video đã chọn được khôi phục để bạn bắt đầu một phiên mới.

Ứng dụng đọc trực tiếp video từ trình chọn tệp, không tạo bản sao của video để
chia sẻ. Màn hình hiển thị rõ trạng thái chuẩn bị, sẵn sàng, đang chia sẻ, đã dừng
hoặc lỗi; liên kết chỉ sao chép được khi phiên chia sẻ đang hoạt động.

## Funscript và The Handy
Trong player, mở **Thêm tùy chọn → Funscript & The Handy** để xem trạng thái
thiết bị, script và đồng bộ. Chọn file `.funscript`/`.csv` hoặc nhập URL; nếu chưa
có Connection Key, ứng dụng sẽ yêu cầu nhập trước khi kết nối.

Công tắc **Đồng bộ với video** được ghi nhớ. Khi tắt, thao tác phát lại, tua và
kiểm tra kết nối không tự bật thiết bị. Khi bật, đồng bộ chỉ chạy lúc video đang
phát và không buffering; video tạm dừng hoặc đứng thời gian VLC sẽ chặn chuyển động.
Script nhập được kiểm tra trước khi thay file đã lưu và gắn với URI video, nên
hai video trùng tên có thể dùng script riêng. Script được tải lên dịch vụ tạm
của Handy để thiết bị đọc.

## Đọc truyện
Trình duyệt truyện có thanh địa chỉ và menu cho điều hướng, tải lại, dấu trang,
thu phóng ảnh và chế độ toàn màn hình. Trang lỗi có nút **Thử lại**; trang đang
đọc và lịch sử điều hướng được giữ khi xoay màn hình.

Với file CBZ/ZIP, ứng dụng hiển thị trạng thái lỗi hoặc file không có ảnh để bạn
thử lại hoặc đóng. Trang đang đọc được giữ khi màn hình tạo lại; mỗi phiên dùng
thư mục tạm riêng để tránh xóa ảnh của phiên khác.

## 📦 Dependencies
- `org.videolan.android:libvlc-all:3.6.0`
- AndroidX AppCompat, RecyclerView, Material Design
