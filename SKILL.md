---
name: lanhu-tinify
description: 使用 Lanhu Resource Assistant IntelliJ/Android Studio 插件，将蓝湖切图或本地图片整理为 Android drawable 资源，并按需使用 Tinify 压缩。
---

# Lanhu Resource Assistant 使用技能

## 适用场景

当需要把蓝湖设计稿、本地图片或图片 ZIP 整理成 Android 项目的 `drawable-*` 资源时，使用本技能。插件运行于 Android Studio/IntelliJ IDEA Tool Window，不抓取蓝湖网页 DOM。

## 完整操作流程

### 1. 打开插件

1. 通过 **View → Tool Windows → Lanhu Resource Assistant** 打开工具窗口。
2. 在顶部 **导入设置** 区域选择 **蓝湖链接导入** 或 **文件导入**。

### 2. 蓝湖登录 Cookie

1. 点击 **打开链接**，在浏览器中完成蓝湖登录、团队授权或分享密码验证。
2. 从浏览器开发者工具复制当前 `lanhuapp.com` Cookie。
3. 将 Cookie 填入最上方 **蓝湖登录 Cookie** 输入框。
4. 点击 **保存 Cookie**。
5. 登录提示会显示保存结果；Cookie 使用 IntelliJ `PasswordSafe` 保存，不写入项目文件，也不会输出到日志。

### 3. 导入蓝湖设计稿

1. 在 **蓝湖链接** 输入分享链接、项目详情链接或单页面详情链接。也可以一次粘贴多条链接，
   每条链接一行；蓝湖复制的带分享人、团队名称和有效期的整段文本会自动提取其中的链接。
2. 选择默认 Android density，支持：
   - `mdpi`
   - `hdpi`
   - `xhdpi`
   - `xxhdpi`
   - `xxxhdpi`
3. 点击 **读取设计稿…**。
4. 在 **设计稿读取状态** 中查看当前读取、解析、下载和完成状态。
5. 多条链接会按顺序读取并汇总页面切图，某个设计稿读取失败不会阻止其他设计稿。
6. 所有设计稿完成汇总后只显示一个统一选择窗口；其中会显示设计稿来源前缀。
7. 在统一选择窗口中逐项设置：
   - 是否导入；
   - 预览；
   - 图片格式：PNG、WebP 或实际可用的 JPG；
   - 蓝湖分辨率：`md`、`hd`、`xhd`、`xxhd`、`xxxhd` 或原图；
   - 目标 Android 目录。
8. 点击 **导入选中图片**，下载后的图片会加入下方图片列表。

如果蓝湖只提供一个 OSS 图片地址，插件会根据图层逻辑尺寸生成不同 density 的 resize 地址，并同步更新预览、像素尺寸和最终下载内容。若接口没有尺寸信息，则提供原图格式转换选项。

### 4. 导入本地图片或 ZIP

1. 切换到 **文件导入**。
2. 点击 **选择图片…** 导入 PNG、JPG、JPEG 或 WebP。
3. 点击 **选择 ZIP…** 导入图片压缩包。
4. ZIP 中的 `drawable-*`、`mipmap-*`、`mdpi`、`hdpi`、`xhdpi`、`xxhdpi`、`xxxhdpi` 路径会自动识别。
5. 导入按钮旁显示读取进度；导入期间暂时拦截其他操作，可点击“取消导入”。
6. 无法识别 density 的图片使用 **回退 density**，默认是 `xxhdpi`。

### 5. 管理图片列表

图片列表展示原始名称、原始大小、像素尺寸、格式、目标名称、目标目录、是否导入和是否压缩。

- **删除选中** 和 **清空列表** 位于表格左下方，只移除插件当前列表，不删除磁盘原文件。
- 目标名称和目标目录单元格可直接编辑。
- 中文名称优先转换为常见英文短语；未匹配的汉字使用本地离线拼音转换。
- 资源名最终只使用小写字母、数字和下划线，不会写入 Unicode 字符。
- 以 `icon` 开头的名称会缩写为 `ic`，例如 `icon_home.png` → `ic_home`。
- 目标文件扩展名支持 PNG、JPG、JPEG 和 WebP。

### 6. 批量编辑

在图片列表下方的 **批量编辑** 区域：

1. 选择目标 Android density。
2. 点击 **应用到选中行** 或 **应用到全部**。
3. 输入名称前缀。
4. 点击 **应用前缀到选中行** 或 **应用前缀到全部**。

图片列表底部的提示说明、登录提示、功能说明、API 安全说明和处理说明使用统一的 IntelliJ 帮助提示样式。

### 7. 配置 Tinify

1. 在 **Tinify API 配置** 中填写 API Key。
2. 点击 **保存密钥**。
3. API Key 通过 IntelliJ `PasswordSafe` 保存，不写入代码、项目文件或日志。
4. 未勾选 **需要压缩** 的图片会直接使用原始字节写入，不需要 API Key。

### 8. 选择目标目录

在 **目标目录** 中填写或选择资源根目录：

- Android module 根目录；
- `src/main/res` 目录；
- `res` 目录；

插件会扫描根目录下已有的 `drawable-*` 和 `mipmap-*` 子目录，并将其作为图片列表“目标资源目录”的可选项，
包括 `drawable-ar-xxhdpi`、`mipmap-xxhdpi` 这类带语言或版本限定符的目录。没有对应 density 子目录时，
才使用标准目录作为待创建目标：

```text
drawable-mdpi
drawable-hdpi
drawable-xhdpi
drawable-xxhdpi
drawable-xxxhdpi
mipmap-mdpi
mipmap-hdpi
mipmap-xhdpi
mipmap-xxhdpi
mipmap-xxxhdpi
```

底部字段是资源根目录，不是最终图片目录；插件最终写入图片列表选定的 `drawable-*` 或 `mipmap-*`。

### 9. 处理并写入资源

1. 确认每行的导入、压缩、目标名称和目标目录。
2. 点击 **处理已导入图片**。
3. Tinify 按每批最多 20 张图片处理，显示进度。
4. 处理期间“处理已导入图片”右侧显示阶段文案和百分比，并暂时拦截其他编辑操作；可使用旁边的取消处理按钮中断处理。
5. 网络临时错误会进行基本重试；支持取消、超时和失败提示。
6. 压缩失败时可选择回退为原始图片字节。
7. 目标文件已存在时，插件会逐项提示；选择“是”覆盖，选择“否”跳过当前文件并继续处理其他图片。
8. 写入后刷新 IntelliJ VFS，资源会显示在 Android 项目中。

## 最终资源结果

每张成功处理的图片会写入用户选择的 Android 资源目录，例如：

```text
app/src/main/res/drawable-xxhdpi/ic_warning_banner.png
```

资源名、目标 density、压缩状态和目标目录均以图片列表中的最终编辑结果为准。

## 支持与限制

- 支持 PNG、JPG、JPEG、WebP。
- SVG、PDF、AVIF 等非当前位图资源不会进入导入列表。
- 蓝湖读取依赖有效 Cookie、团队权限和蓝湖内部接口，接口结构变化可能导致读取失败。
- 蓝湖浏览器端“下载全部切图”生成的是浏览器内 `blob:` ZIP，插件不能直接读取 `blob:` URL；遇到接口无法解析时，应先在浏览器下载 ZIP，再使用文件导入。
- Tinify 每批最多处理 20 张，额度和计费规则遵循 Tinify 账号限制。
- 目标文件同名时必须通过界面确认覆盖。
