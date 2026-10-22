# signage-tv-kiosk

App Android TV (Kotlin) chạy trang signage `http://192.168.0.254:13000/` toàn màn hình. Thay cho app "Trình duyệt INTERNET" trên các TV nhà máy:
- tự hồi phục khi trang bị sập;
- định kỳ khởi động lại để trả bộ nhớ;
- tự chạy khi bật TV.

Trạng thái: **mới có khung (scaffold)**. Yêu cầu và thiết kế đầy đủ nằm ở [`docs/SPEC.md`](docs/SPEC.md).

## Build

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk (tạm ký bằng debug key)
./gradlew testDebugUnitTest
```

Cần JDK 17+ và Android SDK. Đường dẫn SDK đặt trong `local.properties` (không đưa vào git).

## Cài lên TV (dự kiến)

```bash
adb connect <ip-tv>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.youngmin.signagekiosk/.MainActivity
```
