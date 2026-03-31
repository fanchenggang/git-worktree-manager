# 提升交互体验与操作效率规范 (UX Refactoring Spec)

## 为什么需要此功能 (Why)
作为产品经理，经过对当前功能的走查，发现交互上存在两个明显的痛点：
1. **创建流程割裂**：创建工作树时，用户需要依次面对三个独立的系统原生弹窗（输入名称 -> 输入分支 -> 是否复制忽略文件）。这种“挤牙膏”式的交互打断了用户的思维流，且体验不够现代化。
2. **操作入口隐藏/冗余**：“Delete”按钮作为大块文本常驻，显得冗余；而“在终端打开”、“在资源管理器中显示”等高频操作又隐藏在右键菜单中，导致发现成本高、操作链路长。

通过将创建流程整合为统一的表单对话框，并将高频操作提取为鼠标悬浮时的图标按钮，可以大幅提升插件的专业感、美观度以及开发者的操作效率。

## 变更内容 (What Changes)
- **统一创建对话框 (CreateWorktreeDialog)**：
  - 新增基于 `DialogWrapper` 的 `CreateWorktreeDialog`。
  - 对话框包含三个输入项：Worktree Name (文本框)、Branch Name (文本框)、Copy Ignored Files (复选框)。
  - 在 `MyToolWindow.kt` 中移除原有的串行弹窗逻辑，改为单次调用该新对话框。
- **悬浮操作图标 (Hover Action Icons)**：
  - 在 `WorktreeItem` 组件中引入图标按钮（使用 Compose 的 Icon 组件）。
  - 当鼠标悬浮 (`isHovered`) 时，在右侧显示一组小图标：终端 (Terminal)、文件夹 (Explorer)、删除 (Delete)。
  - 右键菜单 (Context Menu) 依然保留以提供如 Merge, Pull 等不常用的分支操作。
- [**BREAKING**] 重构 `WorktreeListContent` 的回调参数，移除多个零散的输入请求回调，简化整体创建链路。

## 影响范围 (Impact)
- 受影响的功能：创建工作树的 UI 流程、工作树列表项的交互与渲染。
- 受影响的代码文件：
  - `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/MyToolWindow.kt`
  - 新增 `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/ui/dialogs/CreateWorktreeDialog.kt`

## 新增需求 (ADDED Requirements)
### 需求：统一的创建对话框
#### 场景：成功整合信息并创建
- **当** 用户点击顶部操作栏的 "Create Worktree" 按钮时
- **则** 弹出一个包含“工作树名称”、“分支名称”和“复制忽略文件复选框”的统一对话框。
- **当** 用户填写完毕并点击 "OK" 时
- **则** 插件获取所有信息，并根据复选框的状态，调用相应的创建逻辑。

### 需求：列表项悬浮图标按钮
#### 场景：快速执行高频操作
- **当** 用户的鼠标指针悬浮在某个工作树列表项上时
- **则** 右侧显示一排操作图标（终端、文件夹、垃圾桶）。
- **当** 用户点击对应图标时
- **则** 触发在终端打开、在系统资源管理器中显示或删除工作树的逻辑。