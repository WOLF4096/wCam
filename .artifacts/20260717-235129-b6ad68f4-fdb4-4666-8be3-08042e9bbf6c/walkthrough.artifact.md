# 重构设置面板 UI

我已完成设置面板的全新 UI 重构，将其从原本的行内下拉框迁移到了独立的设置页面。

## 主要改动

### 1. 顶部导航与入口
- **设置入口**：主界面右上角的“格式”按钮已替换为“设置”。
- **独立页面**：点击“设置”会进入 [SettingsActivity](file:///D:/Project/wCam/wCam/app/src/main/java/com/example/wcam/ui/SettingsActivity.kt)，采用全黑极简设计，完美还原了 `docs/设置面板UI.html` 中的布局。

### 2. 核心功能实现 (格式与算法)
- **保持格式**：
    - 支持 JPG, PNG, RAW 切换（使用 Chip 样式）。
    - JPG 模式下自动显示质量调节滑块。
    - 地理位置保存开关（UI 已就绪，逻辑同步 Preference）。
- **处理方式**：
    - 采用分段选择器（Segmented Control）切换“无”和“去马赛克”。
    - “无”模式支持：RGB 通道、RGB 合并、原始拜尔、灰度。
    - “去马赛克”模式支持：AMaZE, RCD, DCB, LMMSE, IGV, AHD, EAHD, HPHD, VNG4, Fast。
    - 智能联动：当仅选择 RAW 格式时，自动隐藏算法处理卡片。

### 3. UI 占位符
- **倒计时**、**自动拍摄**、**水印**卡片已按照 HTML 布局完成 UI 实现，目前作为功能预留，暂不包含实际拍摄逻辑。

### 4. 数据持久化
- 所有在设置页面修改的参数均会实时保存至 `PreferenceManager`。
- 返回主界面时，预览流和状态文字会自动根据新配置进行刷新（例如绿色状态栏会同步显示新的格式和算法）。

## 验证总结
- **编译成功**：`gradlew :app:assembleDebug` 已通过。
- **UI 一致性**：使用了自定义 Color 和 Drawable（如 `bg_settings_card.xml`, `sl_settings_chip.xml`）确保视觉效果与设计稿高度一致。
- **逻辑兼容**：更新了 `CaptureManager` 的算法映射，确保“原始拜尔”等新标签能正确指向底层算法。

您可以现在启动应用，点击右上角的“设置”体验全新的交互界面。
