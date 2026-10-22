# signage-tv-kiosk

App Android TV (Kotlin) chạy trang signage `http://192.168.0.254:13000/` toàn màn hình. Thay cho app "Trình duyệt INTERNET" trên các TV nhà máy:
- tự hồi phục khi trang bị sập;
- định kỳ khởi động lại để trả bộ nhớ;
- tự chạy khi bật TV.

Trạng thái: **đã code đủ R1–R14, chưa thử thực địa trên TV**. Yêu cầu và thiết kế đầy đủ nằm ở [`docs/SPEC.md`](docs/SPEC.md).

## Build

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk (ký bằng key trong keystore.properties)
./gradlew testDebugUnitTest
```

Cần JDK 17+ và Android SDK. Đường dẫn SDK đặt trong `local.properties` (không đưa vào git).

## Cài lên TV (dự kiến)

> ⚠ **Chữ ký:** `app-release.apk` ký bằng key khai trong `keystore.properties` ở gốc repo (không đưa vào git; gồm `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Thiếu file này thì build ra `app-release-unsigned.apk`. Mọi bản cập nhật cho TV phải ký bằng ĐÚNG key đã cài lần đầu (lệch chữ ký ⇒ phải gỡ app, mất cấu hình và cookie đăng nhập). Kiểm: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`. Không bao giờ cài `app-debug.apk` lên TV thật.

```bash
adb connect <ip-tv>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
# Cho phép tự chạy khi bật TV (Android 10+ chặn mở activity từ nền nếu thiếu quyền này):
adb shell appops set com.youngmin.signagekiosk SYSTEM_ALERT_WINDOW allow
# Mở app một lần sau khi cài (app chưa từng mở sẽ không nhận BOOT_COMPLETED), kèm cấu hình nếu cần:
adb shell am start -n com.youngmin.signagekiosk/.MainActivity --es url 'http://192.168.0.254:13000/#checkin' --ei restart_min 240 --ei low_mem_mb 0
```

Trên TV: nút MENU hoặc bấm BACK 3 lần trong 2 giây để mở hộp cài đặt. Log cục bộ: `adb shell cat /sdcard/Android/data/com.youngmin.signagekiosk/files/kiosk.log`.
