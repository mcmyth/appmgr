# 应用管理

一款面向 Android 9.0 及以上设备的本地应用管理工具。可以浏览用户程序、系统程序和 APK 备份，查看应用详情，并完成备份、安装、卸载、分享等常用操作。

## 功能

- 用户程序、系统程序、备份程序三页无缝左右滑动切换
- 按安装时间或应用名称排序
- 字母索引快速定位，滚动时显示日期或字母提示
- 应用卡片展开、收起和列表更新动画
- 应用启动、卸载、APK 备份、应用市场和 APK 分享
- 单 APK 保存为 `.apk`，拆分 APK 保存为 `.apks`
- 备份安装、删除、分享、应用市场和详情查看
- 备份、卸载、更新和删除后局部更新对应列表项
- 自定义备份目录，显示备份容量与存储使用情况
- 应用详情展示包名、版本、安装时间、APK、数据目录、SDK、原生库和启动组件
- 信息字段长按复制，并显示复制提示
- 应用列表读取进度、数量、百分比和当前应用名称

## 运行要求

- Android 9.0（API 28）或更高版本
- Android Studio 与 JDK 21
- Android SDK 36.1

## 构建

克隆仓库后，在项目根目录执行：

```powershell
.\gradlew.bat assembleDebug
```

生成的调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可以直接使用 Android Studio 打开项目并运行 `app` 配置。

## 权限说明

- `QUERY_ALL_PACKAGES`：读取设备上的应用清单并展示应用信息
- `REQUEST_DELETE_PACKAGES`：调用系统卸载界面
- `REQUEST_INSTALL_PACKAGES`：安装已备份的 APK 或 APKS

应用通过系统文档目录选择器访问用户指定的备份目录。分享 APK 时使用临时授权的内容 URI。

## 数据说明

详情页可以显示应用私有数据目录和 `Android/data/<包名>` 路径。受 Android 应用沙盒与存储限制影响，普通应用无法读取其他应用私有数据目录中的文件。

## 许可证

本项目采用 [MIT License](LICENSE)。
