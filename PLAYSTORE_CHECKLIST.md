# StarGallery — Google Play 上架准备清单

> 本文档为 StarGallery 上架 Google Play 的完整执行指南。按优先级分三阶段执行，完成后即可提交。

---

## 📋 目录

- [阶段零 — 前置准备](#阶段零--前置准备)
- [阶段一 — 代码层修改](#阶段一--代码层修改)
- [阶段二 — 素材准备](#阶段二--素材准备)
- [阶段三 — Play Console 提交](#阶段三--play-console-提交)
- [附录 A — 商店文案草稿](#附录-a--商店文案草稿)
- [附录 B — 数据安全问卷答案](#附录-b--数据安全问卷答案)
- [附录 C — 隐私政策 HTML 模板](#附录-c--隐私政策-html-模板)
- [附录 D — 已就绪确认清单](#附录-d--已就绪确认清单)

---

## 阶段零 — 前置准备

在修改任何代码之前，先完成以下**一次性手动操作**。

### 0.1 创建 release keystore

打开 **PowerShell**（非代码编辑器终端），执行以下命令：

```powershell
keytool -genkey -v -keystore E:\program\StarGallery\app\stargallery-release.jks `
        -keyalg RSA -keysize 2048 -validity 10000 -alias stargallery `
        -storetype JKS
```

执行时按提示输入：

| 提示 | 示例值 | 说明 |
|------|--------|------|
| 输入库密码 | `（自己设置，记下来）` | 即 storePassword |
| 确认库密码 | 重复一次 | |
| 名字与姓氏 | `Geng Xing` | 你的姓名 |
| 组织单位 | `（空）` | |
| 组织名称 | `（空）` | |
| 城市地区 | `（空）` | |
| 省/市/自治区 | `（空）` | |
| 两位国家代码 | `CN` | |
| CN=xxx, OU=xx...是否正确 | `是` | |
| 输入密钥密码 | `（可直接回车=与库密码相同）` | 即 keyPassword |

**⚠️ 重要**：将以下信息手记到密码管理器 + 纸质备份（丢失后无法更新已上架应用！）：
```
keystore 路径: app/stargallery-release.jks
storePassword: _______
keyAlias:      stargallery
keyPassword:   _______
```

### 0.2 创建 keystore.properties

在项目根目录 `E:\program\StarGallery\` 创建文件 `**keystore.properties**`（**不入 git**）：

```properties
storeFile=app/stargallery-release.jks
storePassword=你上面输入的库密码
keyAlias=stargallery
keyPassword=你上面输入的密钥密码
```

### 0.3 确认已安装 JDK 21

```powershell
java -version
# 输出应包含 "21"（如 openjdk version "21.0.1" ...）
```

> JDK 21 是 Android Gradle Plugin 9.x 的要求。

### 0.4 创建 GitHub 仓库保存隐私政策

> 这一步在阶段二之前完成即可。

1. 登录 GitHub → 新建仓库 → 仓库名 `stargallery-privacy` → **Public**
2. 不要勾选任何初始化选项
3. 创建好后把地址（如 `https://github.com/xxx/stargallery-privacy.git`）贴到备忘录

---

## 阶段一 — 代码层修改

> 涉及 6 个文件的修改，完成后运行 `assembleDebug` 确认构建通过。

### 1.1 配置 release signing + 构建优化

**文件**: `app/build.gradle.kts`

在 `android {` 块内、`defaultConfig {` 之后新增：

```groovy
    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("keystore.properties")
            if (keystoreFile.exists()) {
                val props = java.util.Properties().apply {
                    load(keystoreFile.inputStream())
                }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            }
        }
    }
```

修改 `release` 块：

```groovy
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true   // ← 新增
            isDebuggable = false       // ← 新增（显式声明）
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")  // ← 新增
        }
    }
```

完整修改后 `android { }` 块看起来像这样（仅示意关键部分）：

```groovy
android {
    namespace = "com.gxstar.stargallery"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("keystore.properties")
            if (keystoreFile.exists()) {
                val props = java.util.Properties().apply {
                    load(keystoreFile.inputStream())
                }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.gxstar.stargallery"
        // ... 其他不变 ...
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(...)
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // ... compileOptions / buildFeatures 不变 ...
}
```

### 1.2 更新 .gitignore

**文件**: `.gitignore` — 追加以下内容，防止密码泄漏：

```
# Release 签名（不含 git）
keystore.properties
*.jks
*.keystore
```

### 1.3 增加 ACCESS_MEDIA_LOCATION 隐私说明

**文件**: `app/src/main/res/values/strings.xml`

在 `privacy_section_2_content`（第 210 行）中，在 "• 元数据读取：读取EXIF信息（相机型号、拍摄参数等）用于显示标签" 后面添加一行：

```xml
• 位置信息：读取照片中的GPS坐标，在详情页地图中显示拍摄位置（需您授权 ACCESS_MEDIA_LOCATION 权限）
```

原字符串变为：

```xml
<string name="privacy_section_2_content">您设备上的媒体文件仅用于以下目的：\n\n• 显示：按照日期、名称等排序展示您的照片\n• 分类：按相册/文件夹组织您的媒体文件\n• 预览：加载和显示照片缩略图和原图\n• 元数据读取：读取EXIF信息（相机型号、拍摄参数等）用于显示标签\n• 位置信息：读取照片中的GPS坐标，在详情页地图中显示拍摄位置（需您授权 ACCESS_MEDIA_LOCATION 权限）\n• 搜索：根据文件名搜索您的照片\n\n我们不会将您的照片用于任何其他目的，也不会与任何第三方共享。</string>
```

### 1.4 同步 README 权限表

**文件**: `README.md` — 在第 81-85 行的权限表中增加 `ACCESS_MEDIA_LOCATION`：

```markdown
| Android 14+ (API 34+) | READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION |
| Android 13 (API 33) | READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION |
| Android 11–12 (API 30–32) | READ_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION |
```

### 1.5 添加英文翻译

**新文件**: `app/src/main/res/values-en/strings.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- App -->
    <string name="app_name">StarGallery</string>

    <!-- Main -->
    <string name="nav_photos">Photos</string>
    <string name="nav_albums">Albums</string>
    <string name="photos_title">Photos</string>
    <string name="albums_title">Albums</string>
    <string name="all_photos">All Photos</string>
    <string name="favorite">Favorites</string>
    <string name="video">Video</string>
    <string name="search">Search</string>
    <string name="search_hint">Search by file name</string>
    <string name="sort_by">Sort by</string>
    <string name="group_by">Group by</string>
    <string name="clear">Clear</string>
    <string name="cancel">Cancel</string>
    <string name="confirm">Confirm</string>
    <string name="delete">Delete</string>
    <string name="more">More</string>
    <string name="info">Details</string>
    <string name="edit">Edit</string>
    <string name="send">Share</string>
    <string name="hide">Hide</string>
    <string name="restore">Restore</string>
    <string name="no_photos">No photos yet</string>
    <string name="scanning_media">Scanning…</string>

    <!-- About -->
    <string name="about">About</string>
    <string name="version">Version %s</string>
    <string name="privacy_policy">Privacy Policy</string>
    <string name="privacy_policy_title">Privacy Policy</string>
    <string name="privacy_policy_intro">Thank you for using StarGallery. We take your privacy and personal data protection seriously.</string>
    <string name="third_party_title">Third Party Libraries</string>
    <string name="permissions_title">Permissions</string>
    <string name="contact_title">Contact Us</string>
    <string name="contact_email_title">Send Email</string>
    <string name="contact_email">gengxing123@qq.com</string>
    <string name="contact_email_subject">StarGallery Feedback</string>
    <string name="send_email">Choose email app</string>

    <!-- Privacy Policy sections (English) -->
    <string name="privacy_policy_date">Last updated: May 17, 2026</string>
    <string name="privacy_section_1_title">1. Information Collection</string>
    <string name="privacy_section_1_content">StarGallery only accesses local media files (photos and videos) on your device to provide the following features:\n\n• Photo browsing: Display photos and videos in a grid\n• Album management: Automatic organization by folder\n• Photo preview: View photos and basic info\n• Favorites: Mark your favorite photos\n• Hidden photos: Protect your private photos (biometric authentication required)\n• Trash: Safely delete and restore media files\n\nWe do not collect, transmit, or share any personal information to external servers. All data processing is done locally on your device.</string>
    <string name="privacy_section_2_title">2. Use of Information</string>
    <string name="privacy_section_2_content">Media files on your device are used only for the following purposes:\n\n• Display: Show your photos sorted by date, name, etc.\n• Organization: Organize media files by album/folder\n• Preview: Load and display thumbnails and original images\n• Metadata reading: Read EXIF info (camera model, shooting parameters, etc.) for display tags\n• Location: Read GPS coordinates from photos to show shooting location on a map in the detail page (requires ACCESS_MEDIA_LOCATION permission)\n• Search: Search your photos by filename\n\nWe do not use your photos for any other purpose, nor share them with any third party.</string>
    <string name="privacy_section_3_title">3. Data Storage</string>
    <string name="privacy_section_3_content">• Local storage: All photo data is stored locally on your device. We do not create additional copies of your photos\n• App settings: Your preferences (column count, sorting, etc.) are stored in the app\'s private directory\n• Cache: Thumbnails are cached for faster loading; cache files reside in the app\'s private directory and can be cleared at any time\n• No upload: We do not upload any of your photos or videos to any server</string>
    <string name="privacy_section_5_title">4. Third Party Services</string>
    <string name="privacy_section_5_content">This app uses the following open-source libraries:\n\n• Glide: Image loading and caching\n• ZoomImage: Image zoom viewer\n• ExoPlayer: Video player\n• metadata-extractor: EXIF metadata reader\n• Room: Local database\n• Hilt: Dependency injection\n• Navigation Component: Page navigation\n• drag-select-recyclerview: Drag-to-select\n• FastScroller: Fast scroll bar\n• Biometric: Biometric authentication\n• LeakCanary: Memory leak detection (debug only)\n• Kotlinx Coroutines: Async coroutine framework\n\nThese libraries process data only on your local device. They do not collect personal information.</string>
    <string name="privacy_section_7_title">5. Your Rights</string>
    <string name="privacy_section_7_content">You have full control over your data:\n\n• Access: Browse all your photos through the app at any time\n• Deletion: Delete photos using system functions or the app\'s built-in delete feature\n• Permission control: Manage app permissions in system settings\n• Uninstall: Uninstall the app at any time; all app data will be cleared upon uninstall\n• Clear cache: Clear all cached data in app settings</string>
    <string name="privacy_section_9_title">6. Policy Updates</string>
    <string name="privacy_section_9_content">We may update this privacy policy from time to time. Updates will be posted within the app. We recommend reviewing this policy periodically. Significant changes will be announced via app update notes.</string>
</resources>
```

### 1.6 构建验证

执行以下两条命令，确认无错误：

```powershell
# 1. Debug 构建（验证代码无误）
.\gradlew.bat assembleDebug

# 2. Release AAB 构建（验证签名 + R8 无误）
.\gradlew.bat bundleRelease
```

预期结果：
- `assembleDebug` → `BUILD SUCCESSFUL`
- `bundleRelease` → `BUILD SUCCESSFUL`，产物在 `app/build/outputs/bundle/release/app-release.aab`

⚠️ 如果 `bundleRelease` 失败：
- 确保 `keystore.properties` 存在且密码正确
- 确保 `signingConfigs.release` 配置与属性文件匹配
- R8 混淆问题可能需要追加 `proguard-rules.pro`

---

## 阶段二 — 素材准备

### 2.1 生成 Feature Graphic (1024×500)

> 用 Python + Pillow 生成。如果你机器上已安装 uv，直接执行以下脚本。

**脚本**: 在临时目录创建 `generate_feature_graphic.py`（或直接写内容）：

```python
# generate_feature_graphic.py
# 依赖: uv run --with Pillow python build_feature_graphic.py

from PIL import Image, ImageDraw, ImageFont
import math
import os

OUT = os.path.join(os.path.dirname(__file__), "fastlane", "metadata", "android", "zh-CN", "images")
SIZE = (1024, 500)

PALETTE = [
    (255, 203, 5), (255, 138, 0), (255, 59, 92), (233, 30, 120),
    (142, 68, 226), (45, 127, 249), (0, 199, 190), (52, 199, 89),
]

def make_tear_petal(width, length, color, alpha=225):
    from PIL import ImageDraw as Draw
    pad = 6; n = 90
    img = Image.new("RGBA", (width + pad * 2, length + pad * 2), (0,0,0,0))
    d = Draw.Draw(img)
    cx = (width + pad * 2) / 2
    pts = []
    for i in range(n + 1):
        t = i / n
        x_off = (width/2) * (math.sin(math.pi*t) ** 0.85)
        pts.append((cx + x_off, pad + t * length))
    for i in range(n + 1):
        t = 1 - i / n
        x_off = (width/2) * (math.sin(math.pi*t) ** 0.85)
        pts.append((cx - x_off, pad + t * length))
    d.polygon(pts, fill=(*color, alpha))
    return img

def paste_petal(canvas, petal, angle, center):
    rot = Image.new("RGBA", canvas.size, (0,0,0,0))
    pw, ph = petal.size; cx, cy = center
    rot.paste(petal, (int(cx-pw/2), int(cy-ph)), petal)
    rot = rot.rotate(-angle, resample=Image.BICUBIC, center=(cx, cy))
    return Image.alpha_composite(canvas, rot)

def draw_logo(size, cx, cy):
    """在 (cx,cy) 处绘制星花 logo (缩放比例)"""
    scale = size / 4096
    W = int(4096 * scale / 4) * 4
    SS = 4
    W_inner = min(W, 2048)
    SS = max(1, W // 512)

    logo = Image.new("RGBA", (W, W), (0,0,0,0))
    lcx = lcy = W // 2
    plen = int(W * 0.36)
    pw = int(W * 0.155)
    for i in range(8):
        petal = make_tear_petal(pw, plen, PALETTE[i], 200)
        logo = paste_petal(logo, petal, i * 45 + 22.5, (lcx, lcy))

    # 中心眼睛 (白色底 + 环 + 星星)
    layer = Image.new("RGBA", (W, W), (0,0,0,0))
    d = ImageDraw.Draw(layer)
    r = int(W * 0.092)
    d.ellipse((lcx-r, lcy-r, lcx+r, lcy+r), fill=(255,255,255,230))
    ring_r = int(r * 0.78)
    d.ellipse((lcx-ring_r, lcy-ring_r, lcx+ring_r, lcy+ring_r),
              outline=(45,50,70,200), width=max(1,int(r*0.12)))
    star_r = int(r * 0.62)
    for k in range(8):
        a = -math.pi/2 + k * math.pi/4
        rr = star_r if k % 2 == 0 else star_r * 0.30
        lcx + rr*math.cos(a); lcy + rr*math.sin(a)  # no-op, just for reference
    # 四芒星
    pts = []
    for k in range(8):
        a = -math.pi/2 + k * math.pi/4
        rr = star_r if k % 2 == 0 else star_r * 0.30
        pts.append((lcx + rr*math.cos(a), lcy + rr*math.sin(a)))
    d.polygon(pts, fill=(255,205,40,255))
    logo = Image.alpha_composite(logo, layer)
    return logo.resize((size, size), Image.LANCZOS)

def main():
    os.makedirs(OUT, exist_ok=True)
    # 背景：从左到右渐变 (白 -> 浅蓝)
    bg = Image.new("RGBA", SIZE, (0,0,0,0))
    for x in range(SIZE[0]):
        t = x / SIZE[0]
        r = int(248 + 7*t)
        g = int(248 + 6*t)
        b = int(254 - 10*t)
        for y in range(SIZE[1]):
            bg.putpixel((x, y), (r, g, b, 255))

    # Logo
    logo_sz = 340
    logo = draw_logo(logo_sz, 0, 0)
    bg.paste(logo, (60, (SIZE[1] - logo_sz) // 2), logo)

    # 文本
    d = ImageDraw.Draw(bg)
    # "StarGallery" 文字放在 logo 右侧
    text_x = 60 + logo_sz + 50
    text_y = SIZE[1] // 2 - 30

    # 如果有中文字体文件则加载，否则 fallback
    font_paths = [
        r"C:\Windows\Fonts\msyh.ttc",   # 微软雅黑
        r"C:\Windows\Fonts\segoeui.ttf",
    ]
    font_title = None
    font_sub = None
    for fp in font_paths:
        if os.path.exists(fp):
            font_title = ImageFont.truetype(fp, 72)
            font_sub = ImageFont.truetype(fp, 28)
            break
    if font_title is None:
        font_title = ImageFont.load_default()
        font_sub = font_title

    d.text((text_x, text_y), "StarGallery", fill=(40, 50, 80, 255), font=font_title)
    d.text((text_x, text_y + 90), "私密相册 · 隐私优先 · 干净无广告",
           fill=(100, 110, 140, 255), font=font_sub)

    # 英文副标题
    d.text((text_x, text_y + 130), "Private Gallery · Privacy First · Zero Ads",
           fill=(140, 150, 180, 255), font=font_sub)

    # 右侧装饰小圆点
    for i in range(4):
        dx = 920; dy = 80 + i * 120
        r = max(1, int(22 - i * 3))
        pal = PALETTE[i * 2]
        for j in range(-r, r):
            for k in range(-r, r):
                if j*j + k*k <= r*r:
                    px, py = dx + j, dy + k
                    if 0 <= px < SIZE[0] and 0 <= py < SIZE[1]:
                        bg.putpixel((px, py), (*pal, 100))

    out_path = os.path.join(OUT, "featureGraphic.png")
    bg.save(out_path)
    print(f"Saved: {out_path}")

if __name__ == "__main__":
    main()
```

执行：
```powershell
uv run --with Pillow python generate_feature_graphic.py
```

产物：`fastlane/metadata/android/zh-CN/images/featureGraphic.png`

### 2.2 准备 6 张手机截图

> 在**真机**上安装 debug APK 后截图。Play Console 建议使用 9:16 比例（像素 1080×1920 或 1242×2208）。

| # | 截图内容 | 建议拍摄方式 |
|---|---------|------------|
| 01 | **首页网格**— 显示星花 logo 已替换后的照片网格，有 4 列以上 | 首页正常状态截屏 |
| 02 | **详情页大图**— 一张照片全屏显示，显示底部工具栏（收藏/分享） | 点开一张照片截屏 |
| 03 | **相册列表**— 显示文件夹封面和名称 | 切到底部导航"相册"截屏 |
| 04 | **EXIF 筛选**— 打开筛选面板，显示三维相机品牌/型号/镜头多选 | 点右上角 EXIF 筛选按钮截屏 |
| 05 | **暗色主题**— 在深色模式下的首页网格 | 设置→深色模式→截屏 |
| 06 | **搜索功能**— 搜索栏输入关键词，显示搜索结果 | 点搜索图标→输入文字→截屏 |

输出格式：**JPEG 或 24-bit PNG**，文件命名：
```
fastlane/metadata/android/zh-CN/images/phoneScreenshots/
├── 01_home_grid.jpg
├── 02_detail.jpg
├── 03_albums.jpg
├── 04_filter.jpg
├── 05_dark_mode.jpg
└── 06_search.jpg
```

> ⚠️ 截图前确保应用图标已更新为新设计的 F1（星花+四芒星），目前资源已就位，直接安装 `assembleDebug` 产物截即可。

### 2.3 部署隐私政策到 GitHub Pages

1. 将附录 C 的内容保存为 `index.html`
2. 在刚才创建的 `stargallery-privacy` 仓库中：
```powershell
cd stargallery-privacy
echo "# StarGallery Privacy Policy" > README.md
copy index.html .\
git init
git add .
git commit -m "Init privacy policy"
git branch -M main
git remote add origin https://github.com/你的用户名/stargallery-privacy.git
git push -u origin main
```
3. GitHub 网页上：进入仓库 → Settings → Pages → Source 选 `Deploy from a branch` → Branch 选 `main` → `/ (root)` → Save
4. 等待 1-2 分钟，Pages 会生成 URL，格式如：
   `https://你的用户名.github.io/stargallery-privacy/`
5. 把这个 URL 记下来，阶段三需填入 Play Console。

---

## 阶段三 — Play Console 提交

### 3.1 Play Console 账号

1. 打开 https://play.google.com/console/
2. 用 Google 账号登录（建议使用与 `contact_email` 相同的账号）
3. 支付一次性注册费 **\$25 USD**（支持国内 Visa/Mastercard）
4. 创建应用 → 填写应用名称 `StarGallery` → 选择"应用"

### 3.2 商店设置

#### 应用名称
```
StarGallery — 相册
```

#### 简短描述（80 字符内）
```
安全私密的本地相册，支持 EXIF 筛选、隐藏照片、回收站和视频播放。
```

#### 完整描述（4000 字符内）

**中文版：**

```
StarGallery 是一款专注于隐私和安全的本地面相册应用。

▎主要功能

📸 照片浏览
以 3-8 列可调网格浏览照片，支持缩略图快速滚动和日期分隔。

🖼️ 相册管理
按文件夹自动分组，封面预览，数量统计，独立设置（列数/排序/分组），互不干扰。

🔍 EXIF 多选筛选
支持相机品牌、相机型号、镜头型号三维多选过滤。维度间 AND + 维度内 OR，级联自动推导。让您在海量照片中快速找到特定设备拍摄的作品。

⭐ 收藏与排序
按拍摄时间/添加时间排序，三级排序保证一致性。收藏标记实时同步，排序结果与详情页完全一致。

🔐 隐藏照片
生物识别认证（指纹/面部/设备密码）保护，彻底保护您的隐私。只有通过认证才能访问隐藏照片。

🗑️ 回收站
安全删除机制，30 天自动过期提醒。IntentSender 模式确保每次删除都需用户确认。

🎬 视频播放
支持内联视频播放，跨页面保持播放状态。兼容 GIF 和 RAW 格式（DNG/ARW/CR2/CR3/NEF/ORF/RAF/RW2/PEF 等）。

🏷️ 丰富 EXIF 信息
20+ 条 EXIF 字段展示，7 大品牌照片风格/胶片模拟映射（松下/索尼/佳能/尼康/富士/奥林巴斯/宾得），支持 HDR 检测。WGS84→GCJ02 坐标转换，一键跳转地图 App。

🌙 暗色主题
支持浅色/深色模式自适应，Material 3 风格界面。

🔒 隐私优先
完全离线运行，零网络权限，零数据收集，零广告。所有的照片处理均在设备本地完成，不给任何第三方上传的机会。
```

**英文版（同步提交）：**

```
StarGallery is a private, secure local gallery app focused on privacy.

▸ Photo browsing: 3-8 column adjustable grid with fast scrolling
▸ Album management: automatic folder grouping, cover preview
▸ EXIF multi-select filter: filter by camera make, model, and lens (AND between dimensions, OR within)
▸ Favorites & sorting: consistent three-level sorting across grid and detail view
▸ Hidden photos: biometric authentication (fingerprint/face/device password)
▸ Trash: safe deletion with 30-day expiration
▸ Video playback: inline playback across pages, GIF and RAW support
▸ Rich EXIF: 20+ fields, 7 camera brands photo style mapping, HDR detection
▸ Dark theme: light/dark mode with Material 3
▸ Privacy-first: 100% offline, zero network, zero ads, zero data collection
```

#### 应用分类

| 字段 | 值 |
|------|-----|
| 类别 | 摄影 (Photography) |
| 子类别 | — |
| 标签 | `相册`, `gallery`, `photo`, `隐私`, `EXIF`, `本地` |
| 目标年龄 | 18+ 或 全年龄均可（选任一均可，按内容选全年龄即可） |

#### 联系人

| 字段 | 值 |
|------|-----|
| 邮箱 | `gengxing123@qq.com` |
| 电话 | **不填** |
| 网站 | 可留空 |
| 外部隐私政策 URL | `https://你的用户名.github.io/stargallery-privacy/` |

### 3.3 数据安全问卷

> 在此页填写: 应用内容 → 数据安全

| 问题 | 答案 |
|------|------|
| 应用是否收集或共享任何必需的个人数据？ | **否** |
| 应用是否完全仅加密数据传输？ | **是**（仅本地，无需传输） |
| 用户可否请求删除数据？ | **是**（应用内提供删除功能） |

**详细回答（"管理数据"页）：**

| 数据类型 | 是否收集 | 是否共享 | 是否加密 | 是否必需 | 用途 |
|---------|---------|---------|---------|---------|------|
| 设备上的照片/视频 | 否（仅本地读取） | 否 | 本地 | 是 | 核心功能 |
| 设备 ID | 否 | 否 | N/A | 否 | — |
| 应用崩溃日志 | 否（无崩溃 SDK） | 否 | N/A | 否 | — |
| 诊断信息 | 否 | 否 | N/A | 否 | — |
| 位置信息（EXIF GPS） | 否（仅本地读取） | 否 | 本地 | 否 | 可选在地图显示拍摄位置 |

### 3.4 应用内容问卷

| 题目 | 答案 |
|------|------|
| 是否包含广告？ | **否** |
| 是否包含应用内购买？ | **否** |
| 是否使用政府级应用？ | **否** |
| 是否有 COVID-19 相关功能？ | **否** |
| 内容分级 | **PEGI 3 / ESRB E**（漫画暴力或轻度） |

### 3.5 应用内广告/推荐

| 问题 | 答案 |
|------|------|
| 是否展示广告？ | **否** |
| 是否有推广？ | **否** |
| 是否有付费安装？ | **否** |

### 3.6 上传 AAB + 发布

1. 从 `app/build/outputs/bundle/release/app-release.aab` 上传
2. 选择发布轨道：
   - **内部测试**（推荐先发亲测）→ 选 "创建新版本"
   - 验证无问题后 → **正式版**（Production）
3. 填写版本发布说明：

```markdown
**v1.0 首版发布**
- 照片网格浏览（3-8 列可调）
- 相册分类管理
- EXIF 三维多选筛选
- 收藏、隐藏照片（生物识别）
- 回收站安全删除
- 视频播放、GIF/RAW 支持
- 20+ EXIF 字段展示
- HDR 检测与照片风格映射
- 完全离线/零广告/零数据收集
```

4. 提交审核（通常 1-3 天，首版可能更长）

---

## 附录 A — 商店文案草稿

### 应用名称
```
StarGallery - 相册
```

### 短描述 (80 字符)
```
安全私密的本地相册，支持 EXIF 筛选、隐藏照片、回收站和视频播放。
```

### 长描述 (英文, 精选)
```
StarGallery is a private, secure local gallery app.
Browse photos in a 3-8 column grid, filter by camera EXIF data, and protect sensitive photos with biometric authentication.
100% offline. Zero ads. Zero data collection.
```

### 标签
```
相册,gallery,photo,privacy,EXIF,本地相册,隐藏照片,私密相册
```

---

## 附录 B — 数据安全问卷答案

**在 Play Console → 应用内容 → 数据安全 页面填写：**

### 核心声明

1. **是否收集共享数据？** → **否**
   - 理由：应用零网络权限（无 INTERNET），所有数据处理设备本地完成
2. **SDK 是否收集数据？** → **否**
   - 所有 12 个依赖均为 Apache-2.0 开源库，零分析/广告/推送 SDK

### 如果有具体问卷问以下数据类型

| 数据类型 | 是否收集 | 说明 |
|---------|---------|------|
| 位置 (大致/精确) | 否 | 仅读取照片 EXIF 中已有的 GPS 坐标用于显示地图，不上传 |
| 照片/视频 | 否 | 仅本地读取展示，不上传 |
| 生物识别特征 | 否 | BiometricPrompt 系统层完成认证，App 仅接收成功/失败结果 |
| 设备 ID | 否 | 无需 |
| 应用活动 | 否 | 无分析 |
| 崩溃日志 | 否 | 无崩溃 SDK |

---

## 附录 C — 隐私政策 HTML 模板

> 用于 GitHub Pages 部署。将以下内容保存为 `index.html`，直接 push 到 `stargallery-privacy` 仓库。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>StarGallery 隐私政策</title>
<style>
  body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; line-height: 1.6; color: #333; }
  h1 { color: #1a1a2e; border-bottom: 2px solid #e0e0e0; padding-bottom: 10px; }
  h2 { color: #2d4059; margin-top: 30px; }
  p { margin: 10px 0; }
  .update-date { color: #888; font-size: 0.9em; }
  hr { border: none; border-top: 1px solid #eee; margin: 40px 0; }
  .footer { color: #aaa; font-size: 0.85em; text-align: center; margin-top: 60px; }
  .lang-toggle { text-align: right; margin-bottom: 20px; }
  .lang-toggle a { color: #007AFF; text-decoration: none; cursor: pointer; }
  .en { display: none; }
</style>
</head>
<body>
<div class="lang-toggle">
  <a onclick="toggleLang()">English</a>
</div>

<div class="zh">
<h1>StarGallery 隐私政策</h1>
<p class="update-date">最后更新：2026年5月17日</p>

<p>感谢您使用 StarGallery（相册）。我们非常重视您的隐私和个人信息保护。</p>

<h2>一、信息收集</h2>
<p>StarGallery 仅访问您设备上的本地媒体文件（照片和视频），以提供以下功能：</p>
<ul>
  <li>照片浏览：以网格形式展示设备中的照片和视频</li>
  <li>相册管理：按文件夹自动整理照片</li>
  <li>照片预览：查看照片详情和基本信息</li>
  <li>收藏功能：标记您喜欢的照片</li>
  <li>隐藏照片：保护您的隐私照片（需生物识别认证）</li>
  <li>回收站：安全删除和恢复已删除的媒体文件</li>
</ul>
<p>我们不会收集、传输或分享您的任何个人信息到外部服务器。所有数据处理均在您的设备本地完成。</p>

<h2>二、信息使用</h2>
<p>您设备上的媒体文件仅用于以下目的：</p>
<ul>
  <li>显示：按照日期、名称等排序展示您的照片</li>
  <li>分类：按相册/文件夹组织您的媒体文件</li>
  <li>预览：加载和显示照片缩略图和原图</li>
  <li>元数据读取：读取 EXIF 信息（相机型号、拍摄参数等）用于显示标签</li>
  <li>位置信息：读取照片中的GPS坐标，在详情页地图中显示拍摄位置（需您授权 ACCESS_MEDIA_LOCATION 权限）</li>
  <li>搜索：根据文件名搜索您的照片</li>
</ul>
<p>我们不会将您的照片用于任何其他目的，也不会与任何第三方共享。</p>

<h2>三、信息存储</h2>
<ul>
  <li>本地存储：所有照片数据均存储在您的设备本地存储中，我们不会创建任何额外的照片副本</li>
  <li>应用设置：您的偏好设置（如列数、排序方式）存储在应用的私有目录中</li>
  <li>缓存数据：为提升加载速度，我们会缓存部分缩略图，缓存文件存储在应用私有目录，可随时清除</li>
  <li>不上传：我们的应用不会将您的任何照片或视频上传至服务器</li>
</ul>

<h2>四、第三方服务</h2>
<p>本应用使用以下开源库：</p>
<ul>
  <li>Glide：图片加载和缓存库</li>
  <li>ZoomImage：图片缩放查看库</li>
  <li>ExoPlayer：视频播放库</li>
  <li>metadata-extractor：EXIF 元数据读取库</li>
  <li>Room：本地数据库</li>
  <li>Hilt：依赖注入框架</li>
  <li>Navigation Component：页面导航库</li>
  <li>drag-select-recyclerview：拖动多选库</li>
  <li>FastScroller：快速滚动条库</li>
  <li>Biometric：生物识别认证库</li>
  <li>LeakCanary：内存泄漏检测库（仅 debug 版本）</li>
  <li>Kotlinx Coroutines：异步协程框架</li>
</ul>
<p>这些库仅在您的设备本地处理数据，不会收集您的个人信息。本应用不包含任何网络分析、广告或推送 SDK。</p>

<h2>五、用户权利</h2>
<p>您对您的数据拥有完全控制权：</p>
<ul>
  <li>访问权：您可以随时通过应用浏览您的所有照片</li>
  <li>删除权：您可以使用系统功能或应用内置的删除功能删除照片</li>
  <li>权限控制：您可以在系统设置中管理应用权限</li>
  <li>卸载权：您可以随时卸载应用，卸载后所有应用数据将被清除</li>
  <li>清除缓存：在应用设置中可以清除所有缓存数据</li>
</ul>

<h2>六、政策更新</h2>
<p>我们可能会不时更新本隐私政策。更新时，我们会在应用内发布更新通知。建议您定期查看本政策以了解最新内容。重大变更将通过应用更新日志通知您。</p>
</div>

<div class="en">
<h1>StarGallery Privacy Policy</h1>
<p class="update-date">Last updated: May 17, 2026</p>
<p>Thank you for using StarGallery. We take your privacy and personal data protection seriously.</p>

<h2>1. Information Collection</h2>
<p>StarGallery only accesses local media files (photos and videos) on your device to provide core functionality. We do not collect, transmit, or share any personal information to external servers. All data processing is done locally on your device.</p>

<h2>2. Use of Information</h2>
<p>Media files on your device are used only for: display, organization, preview, metadata reading (EXIF), location display (GPS, requiring ACCESS_MEDIA_LOCATION permission), and search. We do not use your photos for any other purpose, nor share them with any third party.</p>

<h2>3. Data Storage</h2>
<p>All photo data remains on your device. App preferences are stored in the app's private directory. Thumbnails are cached locally. No upload occurs.</p>

<h2>4. Third Party Services</h2>
<p>This app uses open-source libraries (Glide, ZoomImage, ExoPlayer, metadata-extractor, Room, Hilt, etc.) that process data only locally. No analytics, advertising, or push SDKs are included.</p>

<h2>5. Your Rights</h2>
<p>You have full control: browse, delete, manage permissions, uninstall, and clear cache at any time.</p>

<h2>6. Policy Updates</h2>
<p>We may update this policy. Changes will be announced within the app.</p>
</div>

<hr>
<div class="footer">
  <p>StarGallery — Private Gallery · Privacy First · Zero Ads</p>
  <p><a href="mailto:gengxing123@qq.com">gengxing123@qq.com</a></p>
</div>

<script>
function toggleLang() {
  var zh = document.querySelector('.zh');
  var en = document.querySelector('.en');
  if (zh.style.display === 'none') {
    zh.style.display = 'block';
    en.style.display = 'none';
  } else {
    zh.style.display = 'none';
    en.style.display = 'block';
  }
}
</script>
</body>
</html>
```

---

## 附录 D — 已就绪确认清单

以下各项已在项目开发过程中完成，**无需**再处理：

| 项 | 状态 |
|---|---|
| `applicationId` 已配置 (`com.gxstar.stargallery`) | ✅ |
| `targetSdk = 35` (满足 2025 年 Play 要求) | ✅ |
| Launcher 图标（5 密度 + adaptive + monochrome + 512px Play Store） | ✅ |
| SplashScreen API (`Theme.App.Starting`) | ✅ |
| 暗色/浅色主题 + v31 edge-to-edge | ✅ |
| 权限说明页面 (`PermissionsFragment`) | ✅ |
| 隐私政策应用内页面 (`PrivacyPolicyFragment`) | ✅ |
| 第三方库列表页面 | ✅ |
| 联系我们（邮件跳转） | ✅ |
| 开源许可页面 | ✅ |
| Room 数据库（v8, 7 段 Migration） | ✅ |
| ProGuard/R8 规则（Glide/Room/Hilt/metadata-extractor/Parcelable） | ✅ |
| FileProvider 安全配置 | ✅ |
| 备份规则（`backup_rules.xml` + `data_extraction_rules.xml`） | ✅ |
| LeakCanary 仅 debug | ✅ |
| 零网络权限、零广告、零分析 SDK | ✅ |

---

> **最后提醒**: keystore 密码**必须**纸质+密码管理器双重备份，丢失后 Play Console 无法更新应用。
> 
> 完成阶段一 + 阶段二后，可随时按阶段三步骤提交 Play Console 审核。
