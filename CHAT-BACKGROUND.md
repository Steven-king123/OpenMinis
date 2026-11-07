# Android 聊天背景图片：本地开发版

## 状态

- 基线：OpenMinis/OpenMinis 及 Steven-king123/OpenMinis/main，`1a71df0f381cefde80bf912b723de6f9831bc177`。
- 已在独立本地工作副本完成源码；尚未编译 APK、未真机验收，不能视为可安装版本。
- 用户更新 Contents 读写权限后，GitHub MCP 已成功从上述基线创建 `feature/chat-background` 分支；该分支用于本功能的源码提交，main 保持不变。
- 之前的 403 已排除；令牌不写入源码或聊天。没有配置自动构建、创建 Release 或发布 APK。

## 已实现

设置 → 外观 → 聊天背景：选择图片、移除、主题遮罩滑块和即时预览。全局应用于现有及新聊天，居中裁切铺满聊天内容区域；标题栏/输入控件保留原有表面。默认遮罩 65%，随当前主题变浅/变深。

图片选择后复制到应用 filesDir/chat-background 私有目录，不依赖图库 URI 长期权限。20 MiB 输入上限，先检查尺寸再采样，最长边不超过2048，处理EXIF方向并重编码JPEG（不保留原始EXIF）；透明区域白底。大图/损坏图失败时保留原背景。导入、移除、设置通过互斥串行化；取消及异常清理临时文件。

不加入聊天附件、不发送模型、不放进 Linux共享目录；排除 Android 系统备份与迁移。Minis自有备份导出按类别扫描已有目录，不包含新私有图片目录。本版背景不随备份迁移，到新设备需重选。没有增加任何系统权限、依赖或数据库迁移。

## 验证

- `python3 verify-chat-background.py`：17/17 静态检查通过（不是编译/设备测试）。
- XML 可解析、英中资源键一致、所有新增字符串引用有定义。
- 新增 ChatBackgroundPolicyTest.kt 三项 JUnit 测试：遮罩边界/NaN、路径名过滤、采样大小及字节限制；尚未执行。
- 独立静态复核未发现具体编译阻断，但不能替代构建。
- 当前沙箱缺 Android SDK/native 构建产物；JDK 安装尝试超时，未获得可用 Java。未声称构建成功。

## 应用补丁

在干净的基线仓库根目录：

```sh
git switch -c feature/chat-background
git apply --check chat-background.patch
git apply chat-background.patch
```

补丁仅包含9个 Android 源码/资源/测试文件；文档和静态验证脚本在 ZIP 内另附。常规构建流程按上游 BUILDING.md（PRoot/rootfs/rclone/native依赖均仍需准备），不要为测试背景破坏既有功能。构建后需验：取消选择、无效/超大图、连续替换、移除、深浅色、旋转/平板/键盘、切换旧会话、重启保留、滚动选择文本和流式回复不被背景拦截。

## 限制

这是全局静态图片背景，不含逐聊天/逐角色背景、裁剪编辑、模糊或动态视频。无图片时维持原界面。未改变应用ID/签名/更新渠道；自编译APK不保证能覆盖安装官方版，不要卸载官方版来试装而未先做好完整备份。
