# CaregiverApp – ngữ cảnh kỹ thuật hiện tại

> Cập nhật theo code ngày 31/08/2026. Đây là tài liệu định hướng để đọc và sửa dự án nhanh; khi tài liệu và code khác nhau, code đang chạy là nguồn sự thật.

## 1. Vai trò

CaregiverApp dành cho người thân/người chăm sóc để:

- đăng nhập, đăng ký và quản lý hồ sơ;
- bật/tắt theo dõi người dùng và phát hiện té ngã;
- gọi video hai chiều với RobotApp;
- lái robot thủ công bằng joystick trong màn hình gọi;
- cấu hình lịch nhắc;
- xem nhật ký nhắc việc, cảnh báo té ngã và cuộc gọi.

Ứng dụng không giao tiếp BLE trực tiếp với ESP32. Lệnh đi qua Firebase tới RobotApp, sau đó RobotApp mới gửi tốc độ bánh xe xuống ESP32.

## 2. Kiến trúc thực tế

~~~
CaregiverApp                         RobotApp
     |                                  |
     +--------- Firebase Auth ----------+
     +---------- Firebase RTDB ---------+
                 |       |
                 |       +-- dữ liệu, cấu hình, trạng thái
                 +---------- lệnh robot và signaling WebRTC

CaregiverApp <====== WebRTC audio/video ======> RobotApp
                         |
                   STUN/TURN bên ngoài

RobotApp -------- HTTPS --------> Groq API
RobotApp -------- BLE ----------> ESP32 --------> động cơ/bánh xe
~~~

Các kết luận quan trọng:

- Firebase Authentication và Firebase Realtime Database là hạ tầng cloud dùng chung.
- Realtime Database đồng thời lưu dữ liệu, truyền lệnh và làm signaling WebRTC.
- Dự án không dùng MQTT.
- Dự án không có signaling server tự triển khai riêng.
- STUN/TURN là hạ tầng kết nối WebRTC, không phải backend dữ liệu.
- Chức năng chatbot dùng Groq nằm trong RobotApp; CaregiverApp không cung cấp màn hình chatbot.
- Dự án hiện không dùng Gemini.

## 3. Công nghệ và cấu trúc

- Android/Kotlin, XML View Binding, minSdk 23, targetSdk 36.
- Firebase Auth và Firebase Realtime Database.
- WebRTC native Android.
- Foreground service cho cuộc gọi đến.

Package chính: com.example.datn_caregiver.

Các file quan trọng:

| Khu vực | File |
|---|---|
| Khởi động/điều hướng | app/src/main/java/com/example/datn_caregiver/MainActivity.kt |
| Đăng nhập/đăng ký | LoginActivity.kt, SignUpActivity.kt |
| Điều khiển tính năng robot | tracking_fragment.kt |
| Video call và joystick | video_call_fragment.kt |
| Cuộc gọi đến nền/cảnh báo té ngã | IncomingCallService.kt, IncomingCallActivity.kt |
| Lịch nhắc | ScheduleFragment.kt, AddScheduleFragment.kt |
| Nhật ký | DiaryFragment.kt và các Diary*Fragment |
| Hồ sơ | ProfileFragment.kt |

## 4. Khởi động và xác thực

- AndroidManifest hiện đặt MainActivity làm LAUNCHER.
- LoginActivity và SignUpActivity vẫn có luồng Firebase Authentication.
- MainActivity khởi động IncomingCallService và quản lý các fragment chính.

Vì launcher đi thẳng vào MainActivity, cần xem đây là quyết định demo hay lỗi auth gate trước khi phát hành. Nếu yêu cầu bắt buộc đăng nhập, phải kiểm tra FirebaseAuth.currentUser và chuyển tới LoginActivity khi chưa có phiên hợp lệ.

## 5. Điều khiển robot

### 5.1. Các node Firebase

- robot_follow_enabled: bật/tắt auto-follow;
- ai_fall_detection_enabled: bật/tắt phát hiện té ngã;
- robot_control: lệnh di chuyển x, y, ts.

Khi tắt auto-follow, app gửi lệnh dừng để tránh robot tiếp tục chạy theo snapshot trước đó. Khi dùng joystick, app tắt auto-follow để hai nguồn lệnh không tranh nhau.

### 5.2. Hợp đồng joystick

Lệnh được ghi vào:

~~~
robot_control/
  x: Number
  y: Number
  ts: Unix epoch milliseconds
~~~

Quy ước runtime đã được hiệu chỉnh theo robot thật:

- x là kênh tiến/lùi;
- y là kênh quay đã bù theo chiều joystick;
- x = 0 và y = 0 là dừng.

Joystick trong video_call_fragment ánh xạ:

~~~
x = vertical displacement / radius
y = -horizontal displacement / radius
~~~

Với hệ tọa độ màn hình Android, kéo lên tạo x âm và kéo trái tạo y dương.
Mapping này là calibration cũ đã chạy đúng trên robot thật; không đổi dấu chỉ
dựa trên tên biến. App throttle lệnh khoảng 20 Hz, gắn timestamp cho mỗi lệnh
và gửi STOP khi thả joystick/kết thúc điều khiển.

RobotApp là nơi kiểm tra độ mới, giới hạn giá trị và đổi x/y thành target hai
motor theo chiều lắp thực tế.

## 6. WebRTC và signaling

- Audio/video đi trực tiếp qua WebRTC hoặc qua TURN nếu P2P không khả dụng.
- Firebase RTDB node webrtc_signal chứa status, callerRole, offer, answer, ICE
  candidates và tùy chọn `emergencyReason`.
- CaregiverApp có thể gọi RobotApp hoặc nhận cuộc gọi từ RobotApp.
- IncomingCallService lắng nghe webrtc_signal khi app ở nền và hiển thị notification/full-screen activity.
- Khi `emergencyReason="fall"`, service hiển thị tiêu đề cảnh báo nghi té ngã và
  nội dung cho biết robot đang gọi khẩn cấp vì chưa xác nhận được an toàn.
- call_history lưu kết quả cuộc gọi.
- Không có signaling server riêng trong repository.
- Khi cuộc gọi hoạt động, RobotApp dùng chính frame camera WebRTC cho YOLO nên
  có thể bật auto-follow trong panel video mà không mở thêm camera trên tablet.

Khi sửa luồng gọi, cần giữ đồng bộ schema với RobotApp và kiểm tra vòng đời camera/micro, cleanup listener, trạng thái calling/connected/ended.

## 7. Lịch nhắc và nhật ký

- Schedules/{uid}/{scheduleId}: lịch nhắc theo người dùng.
- reminder_logs/{uid}: lịch sử thực hiện/bỏ lỡ lời nhắc.
- fall_alerts: mỗi sự cố có một record do RobotApp tạo; record được cập nhật từ
  pending sang an toàn/nguy hiểm/không phản hồi và kèm bằng chứng hậu kiểm như
  verificationReason, visibleKeypoints, bboxFullyInside, upperBodyYRatio.
- call_history: lịch sử cuộc gọi.
- Users/{uid}: hồ sơ người dùng.

RobotApp đọc lịch và thực hiện nhắc; CaregiverApp tạo, sửa, bật/tắt và xem lịch sử.

Một số node như robot_control, webrtc_signal, fall_alerts, call_history và các cờ tính năng đang dùng phạm vi toàn cục. Cách này phù hợp demo một robot nhưng là rủi ro khi có nhiều tài khoản/robot. Nếu đổi sang users/{uid}/robots/{robotId}, phải migration đồng thời cả hai app và Firebase Security Rules.

## 8. Liên hệ với ESP32

CaregiverApp không biết chi tiết BLE và không gửi payload motor trực tiếp:

~~~
Caregiver joystick
  -> Firebase robot_control {x, y, ts}
  -> RobotControlManager trong RobotApp
  -> BLE L<float>R<float>\n
  -> ESP32
  -> hai motor
~~~

Firmware nằm tại:

E:\LearnAndroidApp\Firmware_OhmniRobot\Code

Firmware dùng ESP32 + SimpleFOC 2.2.2, nhận lệnh BLE, giới hạn tốc độ và có watchdog tự dừng.

## 9. Vấn đề cần lưu ý

- MainActivity đang là launcher nên có thể bỏ qua màn hình đăng nhập.
- Nhiều node RTDB chưa tách theo userId/robotId.
- Cần kiểm thử Firebase Security Rules cho dữ liệu điều khiển, hồ sơ và signaling.
- Cần kiểm thử thực tế chiều joystick và chiều motor sau thay đổi contract.
- Cấu hình STUN/TURN và quyền notification/full-screen intent cần kiểm tra trên thiết bị thật.
- Secret/API key không được hard-code hoặc commit.

## 10. Kiểm tra sau khi thay đổi

Từ thư mục CaregiverApp:

~~~
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
~~~

Khi sửa contract Firebase, signaling hoặc command, phải kiểm tra đồng thời CaregiverApp, RobotApp và firmware.
