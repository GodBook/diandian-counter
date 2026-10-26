# 点点计数

中文离线 Android 计数器，Android 8.0 及以上可用。安装包在 `artifacts/diandian-counter-v1.0.0.apk`。

## 使用

- 点“新建”创建计数器，可命名并选择五种卡片颜色。
- 轻触卡片任意空白、名称、数字或 `+1` 区域，计数加一。点击右上角管理按钮或长按卡片，可编辑、手动归零、查看记录或删除。
- 自动重置可设为不启用、每天、每周、每月，或每隔若干分钟／小时／天。每个计数器使用独立周期。
- “操作记录”支持按计数器（包括已删除的计数器）、操作类型筛选和分页查看。右上角导出按钮将**全部**记录导出为 UTF-8 CSV，可用 Excel 或文本编辑器打开。
- 设置中可以关闭点击震动，并管理 Android 的“闹钟和提醒”权限。

## 周期与记录

日、周、月周期按手机本地时间执行。每周从周一编号，每月 29／30／31 日遇到较短的月份时，在当月最后一天执行；下个月仍使用最初设置的日期。固定间隔从保存新周期时起算，范围为 1 分钟至 10 年。

手动归零、改名和改色不会改变已安排的自动重置时间。修改实际周期才会重新计算下一次时间。重置前的数值写入日志；关机或错过多个周期后恢复运行，会逐次补记每个周期的重置，后续空周期记为 `0 → 0`。

记录包括新建、每次加一、改名、改色、修改周期、手动／自动归零、删除、应用打开／进入后台、主页面切换、筛选、设置、管理菜单和编辑窗口的打开／取消、选择或取消导出位置、成功导出。每条记录保存时间、计数器 ID 和当时的名称、操作类型、前后数值及详情。自动重置额外保留计划执行时间。计数器与相应记录在同一个 SQLite 事务中提交，删除计数器不会删除日志，同名重建也不会合并历史。

Android 可能延后后台闹钟，尤其是未授权精确闹钟、系统省电、强行停止或关机时。设置页说明如何授权；重开应用时会补记到期重置。应用没有网络权限，不上传数据，不会自动清理日志。卸载或清除应用数据会丢失本机数据；请定期导出 CSV。CSV 是可读的操作备份，本版本没有导入恢复功能。

## 开发与构建

原生 Java 17 + Android SDK 36 + Gradle 8.14.2 / Android Gradle Plugin 8.10.1，无第三方运行时依赖。最低 SDK 26，目标 SDK 36。无后台常驻服务。

1. 安装 JDK 17 或兼容版本、Android SDK 36、Build Tools。
2. 设置 `ANDROID_HOME`，或在不纳入 Git 的 `local.properties` 中设置 `sdk.dir`。Windows 属性文件的盘符冒号应转义，例如 `sdk.dir=D\:/dev/android-sdk`。
3. 本工作目录已经生成独立发布密钥及 `signing.properties`，两者均被 Git 忽略。请备份 `.signing/diandian-release.jks` 与 `signing.properties`，未来覆盖升级需要使用同一把密钥。新的开发环境可以参考 `signing.properties.example`，并用 `keytool -genkeypair` 自建密钥。
4. PowerShell 7 中执行：

```powershell
.\tools\build.ps1 -JavaDirectory 'D:\dev\jdk-17'
```

脚本执行单元测试、Android Lint、发布构建，然后将签名 APK 复制到 `artifacts`。默认将中间文件放入 `%LOCALAPPDATA%\DianDianCounter\build` 下，避免 Windows 中文路径导致测试进程找不到类；可用 `-OutputDirectory` 指定其他无中文的路径。

也可手动执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lint :app:assembleRelease '-PcounterBuildDir=D:/dev/diandian-counter-build'
```

## 验证

`app/src/test` 包含周期边界、月末／闰年、夏令时、非法参数和 CSV 转义测试。`app/src/androidTest` 使用隔离数据库验证连续与并发计数、完整日志、错过周期补记、幂等性、分页、删除后保留历史、导出、数值溢出和持久化。

在专用测试模拟器上构建并运行设备测试（不要指向有个人数据的设备）：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest '-PcounterBuildDir=D:/dev/diandian-counter-build'
adb -s emulator-5582 install -r D:/dev/diandian-counter-build/app/outputs/apk/debug/app-debug.apk
adb -s emulator-5582 install -r D:/dev/diandian-counter-build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w cn.diandian.counter.tests/cn.diandian.counter.CounterTestRunner
```

设备测试输出 `PASS` 才表示通过。参数 `-e mode ui` 运行实际界面流程，要求专用模拟器上的应用数据为空。后台闹钟使用 `-e mode alarm` 安排 15 秒后的归零并退出进程，再在到期后使用 `-e mode verify-alarm` 验证结果；验证模式不会主动补记，从而确认操作确实由后台接收器执行。Android 12 及以上测试精确闹钟时，先在系统设置中授予“闹钟和提醒”权限；不授权时系统可能延后执行。

业务代码在 `app/src/main/java/cn/diandian/counter`：`MainActivity` 为原生界面，`CounterStore` 管理 SQLite 事务和日志，`ResetRule` 计算周期，`ResetScheduler` 与 `ResetReceiver` 安排后台归零、开机恢复和时间变更后的重新调度。
