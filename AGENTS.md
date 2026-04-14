# StarGallery - Agent 指导

## 构建命令 (Windows)

```bash
gradlew.bat assembleDebug   # Debug 构建
gradlew.bat installDebug    # 构建并安装到设备
gradlew.bat clean           # 清理构建缓存
```

## 关键架构点

### Paging 3 + Date Headers
- `PhotoPagingSource` 从 MediaStore 分页加载数据，每页默认 50 项
- `PhotoPagingAdapter` 使用 `insertSeparators` 插入日期分隔符
- SeparatorItem 占整行显示日期标题（日/月/年分组）
- 使用 `MediaChangeDetector` 监听文件变化自动刷新

### 拖动多选实现
- **DragSelectHelper**: 管理选择状态、处理拖动事件、计算连续范围
- **PhotoSelectionManager**: 封装业务逻辑，提供 startDragSelection() 等接口
- **Bug 修复**: idToPosition 映射校准 - 在 PhotoSelectionManager.startDragSelection() 和 DragSelectHelper 中添加 findCorrectPosition() 方法，确保选择圆圈显示正确位置
- 支持全选、取消全选、连续拖动选择

### MediaStore API 流程
1. Repository 层返回 IntentSender（而非直接执行）
2. UI 层通过 registerReceiver() 监听结果
3. 用户确认后回调处理成功/失败
4. 支持批量操作和单张照片操作
5. **关键方法**:
   - `toggleFavorite(photo)`: 收藏单张，返回 IntentSender
   - `setFavorite(photos, isFavorite)`: 批量收藏，返回 IntentSender
   - `deletePhoto/photo` / `trashPhoto/photo`: 删除/回收站操作
   - `deletePhotos/trashPhotos/restorePhotos`: 批量操作

### MVVM + Repository Pattern
- **UI 层**: Fragment/ViewModel 处理用户交互、事件响应、IntentSender 回调
- **Repository 层**: MediaRepository 封装所有数据访问逻辑，统一处理 MediaStore API
- **Data 层**: Room Database (可选)、MediaStore API、本地扫描器 (MetadataScanner)

## RAW 配对实现

同名 JPG+RAW 文件自动关联：
1. 解析文件名提取基础名（不含扩展名）
2. 检查是否存在对应的 RAW 文件
3. 在 PhotoPagingAdapter 中渲染 "JPG+RAW" 标签
4. 点击可切换查看配对的另一张照片
5. 支持单独显示 RAW 格式分组

## Fragment 导航指南

修改导航需更新 `res/navigation/nav_graph.xml`：
1. 在 nav_graph.xml 中添加新的 Fragment 节点
2. 使用 SafeArgs 传递参数（如 photoId, albumId）
3. 在目标 Fragment 的 onNavigate() 中接收参数
4. 更新 AGENTS.md 中的导航说明

## 技术栈

| 库 | 版本 |
|----|------|
| Kotlin | 2.3.20 |
| AGP | 9.1.0 |
| Paging | 3.4.0 |
| drag-select-recyclerview | 2.4.0 |
| Glide | 4.16.0 |
| Media3 (ExoPlayer) | 1.10.0 |
| SubsamplingScaleImageView | 3.10.0 |
| Hilt | 2.59.2 |

## 开发约定

- **ViewBinding** 替代 findViewById，使用 `viewBinding.root` 访问根视图
- **SafeArgs** 进行 Fragment 参数传递，避免 Bundle 手动管理
- **IntentSender 流程**: MediaStore 收藏/删除/回收站操作需用户确认，必须设置回调处理结果
- **日期分组**: 使用 Paging 3 `insertSeparators` 实现，按日/月/年自动分组
- **RAW 配对**: 同名 JPG+RAW 文件自动合并显示，支持标签切换查看
- **ExifExtractor**: 通过 ExifInterface 提取拍摄时间、相机型号等 EXIF 信息

## Fragment 导航说明

使用 SafeArgs，参数通过 `PhotosFragmentDirections` 传递。修改导航需更新 `res/navigation/nav_graph.xml`。

## 快速验证

修改 Kotlin 代码后运行 `gradlew.bat assembleDebug` 确认编译通过。

*最后更新：2026-04-14*
