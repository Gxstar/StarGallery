# SDD Progress Ledger

## Projects
**Phase A** (spec: `docs/superpowers/specs/2026-07-08-m3-dynamic-color-design.md`): ✅ 完成
**Phase B** (spec: `docs/superpowers/specs/2026-07-08-m3-tokenization-design.md`): 进行中

## Phase A Progress
- ✅ Task 1: 提升 minSdk 30→31 (cf3edc4)
- ✅ Task 2: 启动页 DynamicColors (8903241)
- ✅ Task 3: StarGalleryApp 接入 DynamicColors (703fb52)
- ⬜ Task 4: 设备回归 (跳过，无 adb 设备)

## Phase B Progress

- ✅ Task 1: B3 - 静态 scheme + surface token (c1f6274, material 1.11.0→1.12.0)
- ⬜ Task 2: B1-batch A - 设置/关于组 (~8 file, ~70 处替换)
- ⬜ Task 3: B1-batch B - 网格/详情组
- ⬜ Task 4: B1-batch C - 其他 layout
- ⬜ Task 5: B2 - drawable 颜色 token 化
- ⬜ Task 6: B4 - typography token 化
- ⬜ Task 7: B5 - 回归验证

## Phase B Notes
- B3: `colorScrim` + `colorSurfaceContainerLow` removed from theme (Material 1.12.0 v8 compat issue)
- B2 简化: 不重写 bg_*.xml 结构，只更新 colors.xml 中对应 key 的值
