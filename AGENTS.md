# StarGallery - Agent 指导

## 构建命令 (Windows)

```bash
gradlew.bat assembleDebug   # Debug 构建
gradlew.bat installDebug   # 构建并安装
gradlew.bat clean          # 清理
```

## 关键架构点

- **Paging 3 + Date Headers**: `PhotoPagingAdapter` 使用 `insertSeparators` 实现日期分隔符，SeparatorItem 占整行
- **拖动多选**: `DragSelectHelper` 管理选择状态，`PhotoSelectionManager` 封装业务逻辑；`idToPosition` 映射需在操作前校准
- **MediaStore 操作**: 收藏/删除/回收站都使用 `IntentSender` 流程，需用户确认
- **MVVM + Repository**: UI 事件在 Fragment/ViewModel，数据操作在 MediaRepository

## 多选功能 (已修复 Bug)

长按选择时 `idToPosition` 可能与实际位置不同步，导致选择圆圈显示错误位置。
修复在 `PhotoSelectionManager.startDragSelection()` 和 `DragSelectHelper` 中添加了 `findCorrectPosition()` 校准逻辑。

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

- **ViewBinding** 替代 findViewById
- **SafeArgs** 进行 Fragment 参数传递
- **IntentSender 流程**: MediaStore 收藏/删除/回收站操作需用户确认，需设置回调处理结果
- **日期分组**: 使用 Paging 3 `insertSeparators` 实现，按日/月/年分组
- **RAW 配对**: 同名 JPG+RAW 文件自动合并，显示 "JPG+RAW" 标签

## Fragment 导航

使用 SafeArgs，参数通过 `PhotosFragmentDirections` 传递。修改导航需更新 `res/navigation/nav_graph.xml`。

## 快速验证

修改 Kotlin 代码后运行 `gradlew.bat assembleDebug` 确认编译通过。

*最后更新: 2026-04-09*
