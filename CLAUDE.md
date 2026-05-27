# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

# 项目专属说明

**StarGallery** — 现代化 Android 本地图库应用。详见 `AGENTS.md` 获取完整技术参考。

## 快速参考

### 构建
```powershell
.\gradlew.bat assembleDebug          # Debug APK
.\gradlew.bat testDebugUnitTest       # 单元测试
```

### 导航
`nav_graph.xml` 定义所有路由，SafeArgs 传递参数，禁止手动 Bundle。

### 关键约束
- ViewBinding 替代 findViewById
- Hilt DI 用 KSP（非 kapt）
- 数据流：Room Flow → ViewModel combine → ListAdapter.submitList()
- MediaStore 操作通过 IntentSender（用户确认后执行）
- 排列统一用 `SortUtils.sortPhotos()`（网格 + 详情页一致）
- 预加载量 = currentSpanCount × 3（图片）/ × 4（布局预取）

### 代码风格
- Kotlin 命名：`lowerCamelCase` 变量/方法，`PascalCase` 类
- 不写注释（除非解释 WHY，而非 WHAT）
- 不写空行分隔方法
- 字符串资源放在 `strings.xml`

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
