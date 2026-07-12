# YShell 主题与 CSS 分层规范

本文档定义 YShell 的 JavaFX CSS 主题分层规则。目标是让颜色、控件默认样式、页面布局职责清晰分离，避免每个页面 CSS 重复维护颜色、边框和组件皮肤。

## 文件职责

### `theme-dark.css` / `theme-light.css`

只存放主题常量，也就是 token 的具体取值。

这些文件回答“这个主题下某个语义颜色是什么值”，不回答“哪个组件应该怎么用它”。

应该放在这里：

- 背景色 token：`-fx-bg-primary`、`-fx-bg-secondary`、`-fx-bg-tertiary`
- 文本色 token：`-fx-text-primary`、`-fx-text-secondary`、`-fx-text-muted`、`-fx-text-info`、`-fx-text-success`、`-fx-text-warning`、`-fx-text-error`
- 边框色 token：`-fx-border-default`
- 输入框 token：`-fx-input-bg`、`-fx-input-border`、`-fx-input-focus-border`
- 按钮 token：`-fx-button-primary-bg`、`-fx-button-cancel-bg`、`-fx-button-danger-bg`
- 表格 token：`-fx-table-header-bg`、`-fx-table-row-hover-bg`
- Tab token：`-fx-tab-bg`、`-fx-tab-active-bg`、`-fx-tab-hover-bg`
- 终端 token：`-fx-terminal-bg`、`-fx-terminal-text`

不应该放在这里：

- `.button { ... }`
- `.table-view { ... }`
- 页面类名，例如 `.cmd-header`
- padding、spacing、min-width、font-size 等布局或组件样式

### `theme-variables.css`

存放全局组件皮肤和通用组件样式。

这些规则应该只描述“原生控件默认长什么样”，不描述某个页面的布局位置和间距。

应该放在这里：

- `Button`、`Label`、`TextField`、`TextArea`、`ComboBox`、`Spinner`
- `ListView`、`TreeView`、`TableView`
- `ScrollPane`、`SplitPane`、`TabPane`
- `CheckBox`、`RadioButton`
- 全局滚动条、上下文菜单
- 组件状态：hover、focused、selected、disabled
- 通用功能样式：`button-primary`、`button-cancel`、`button-danger`、`hbox-input`

原则上可以影响：

- 颜色
- 圆角
- 边框
- 默认字体颜色
- 默认图标颜色
- 控件内部结构颜色，例如 TableView header、row hover

原则上不应该影响：

- 页面布局距离
- 页面局部宽高
- 页面特定 padding / spacing
- 某个业务模块的专属结构，例如 `.cmd-toolbar`

### 页面 CSS

例如：

- `command-dialog.css`
- `files-view.css`
- `connection-editor.css`
- `settings-manager.css`

页面 CSS 只负责页面结构、布局和少量页面语义层级。

应该放在页面 CSS：

- 页面根节点尺寸、边框
- 页面区域布局：padding、spacing、alignment、min-width、pref-height
- 页面语义背景：header、toolbar、sidebar、content、footer
- 页面专属图标语义：文件夹图标、命令图标、状态点
- 页面专属组件尺寸：树行 padding、表格列 label padding

不应该重复写：

- 普通按钮默认颜色
- 普通输入框默认背景和边框
- 普通表格 header/row hover/selected 背景
- 普通 TreeView/ListView 背景
- 只为了“和主题一样”的颜色覆盖

## 背景色层级

### `-fx-bg-primary`

主画布、主内容区、数据区。

适用场景：

- 页面根内容区
- 文件列表区域
- 表格主体
- 编辑器主体
- 主工作区

它应该是页面中最大面积、最安静的底色。

### `-fx-bg-secondary`

承载控件的操作区域。

适用场景：

- toolbar
- footer
- 表单内容块
- 搜索/输入操作区
- 弹窗内部次级承载区

它用于从主内容区轻微抬起一层。

### `-fx-bg-tertiary`

结构强调层。

适用场景：

- title bar
- sidebar
- navigation
- section header
- tab strip
- 分组标题

它不应该大面积铺满主内容区，否则页面层级会显得重。

### `transparent`

纯布局容器默认透明。

适用场景：

- 只负责排列的 `HBox` / `VBox` / `StackPane` / `BorderPane`
- 不承担视觉分层的 wrapper
- 组件内部透明覆盖，例如搜索框里的内嵌 `TextField`

不要因为节点是容器就给它背景色。只有它承担 header、toolbar、sidebar、content 等语义时才设置背景。

### `-fx-input-bg`

输入控件专用背景。

适用场景：

- `TextField`
- `PasswordField`
- `TextArea`
- `ComboBox`
- 伪输入框容器，例如 `hbox-input`

不要用 `primary` 或 `secondary` 假装输入框背景。

### 状态背景

只用于交互状态：

- `-fx-bg-hover`
- `-fx-bg-selected`
- `-fx-bg-disabled`

不要把这些 token 用作静态容器背景。

### 强调与状态 token

只用于状态、强调、危险操作和选中态。

适用场景：

- success / warning / error 状态
- primary action
- active indicator
- 选中状态的强调线

不要用于大面积页面背景。

当前主题没有单独的 `accent-*` token。状态和强调应使用现有语义 token：

- 状态文字：`-fx-text-info`、`-fx-text-success`、`-fx-text-warning`、`-fx-text-error`
- 主要按钮：`-fx-button-primary-bg`
- 危险按钮：`-fx-button-danger-bg`
- 聚焦/强调线：`-fx-input-focus-border`

## 判断顺序

设置背景色前按顺序判断：

1. 这个节点只是布局容器吗？
   使用 `transparent`，或者不写背景。

2. 它是页面主内容底色吗？
   使用 `-fx-bg-primary`。

3. 它是工具栏、操作区、表单块、底部栏吗？
   使用 `-fx-bg-secondary`。

4. 它是侧栏、标题栏、导航块、分组 header 吗？
   使用 `-fx-bg-tertiary`。

5. 它只是为了分隔两个区域吗？
   优先使用 `-fx-border-color`，不要为了分隔而加一层背景。

6. 它是 hover、selected、active、disabled 状态吗？
   使用状态 token，不要复用静态背景色。

## 边框颜色

边框颜色使用主题 token：

- 默认分隔：`-fx-border-default`
- 聚焦：`-fx-input-focus-border`

页面 CSS 可以写边框位置和宽度：

```css
.cmd-toolbar {
    -fx-border-color: -fx-border-default;
    -fx-border-width: 0 0 1px 0;
}
```

不要在页面 CSS 写具体颜色值。

如果只是分隔两个区域，优先用 border，不要额外制造背景层级。

## 文本颜色

文本颜色使用语义 token：

- 主文字：`-fx-text-primary`
- 次级文字：`-fx-text-secondary`
- 弱提示：`-fx-text-muted`
- 反色文字：`-fx-text-inverse`
- 信息/状态：`-fx-text-info`、`-fx-text-success`、`-fx-text-warning`、`-fx-text-error`

默认 `Label`、输入控件、表格文字等应该在 `theme-variables.css` 中统一。

页面 CSS 只在有明确语义时设置文本色，例如：

- 状态文字
- 分组标题
- 弱提示
- active tab

## 图标颜色

图标颜色同样按语义设置，而不是按 `FontIcon` 类型全局硬套。

推荐语义：

- 普通图标：默认跟随 `-fx-text-primary`
- 弱图标：`-fx-text-muted`
- 文件夹：`-fx-icon-folder`
- 连接类型：`-fx-icon-connection-windows`、`-fx-icon-connection-linux`
- 成功：`-fx-icon-status-success` 或 `-fx-text-success`
- 错误：`-fx-icon-status-error` 或 `-fx-text-error`

不建议写：

```css
FontIcon {
    -fx-icon-color: -fx-text-muted;
}
```

这会误伤状态图标、功能图标和业务图标。

推荐按语义类：

```css
.cmd-search-icon {
    -fx-icon-color: -fx-text-muted;
}

.cmd-icon-folder {
    -fx-icon-color: -fx-icon-folder;
}

.cmd-icon-command {
    -fx-icon-color: -fx-icon-connection-linux;
}
```

如果多个页面重复出现，可以再抽到 `theme-variables.css` 的通用语义类。

## 原生控件默认皮肤

以下控件默认皮肤应由 `theme-variables.css` 统一维护：

- `Button`
- `Label`
- `TextField`
- `PasswordField`
- `TextArea`
- `ComboBox`
- `Spinner`
- `CheckBox`
- `RadioButton`
- `ListView`
- `TreeView`
- `TableView`
- `ScrollPane`
- `SplitPane`
- `TabPane`
- `ContextMenu`

页面 CSS 不应该重复写这些控件的默认颜色。

例如 `TableView` 表头背景统一使用：

```css
.table-view .column-header-background {
    -fx-background-color: -fx-table-header-bg;
}
```

页面表格只写尺寸、padding、字体等：

```css
.key-table .column-header .label {
    -fx-font-size: 12px;
    -fx-padding: 8 10;
    -fx-alignment: center-left;
}
```

页面也可以调整全局控件的局部尺寸，但不要重复写控件皮肤颜色。例如设置页的紧凑数字输入框：

```css
.settings-manager .settings-spinner {
    -fx-pref-height: 28px;
    -fx-max-width: 112px;
}

.settings-manager .settings-spinner .text-field {
    -fx-padding: 0 8px;
}
```

`Spinner` 的背景、边框、focus、箭头按钮颜色应由 `theme-variables.css` 的 `.spinner` 规则统一维护。

## 页面 CSS 示例：`command-dialog.css`

`command-dialog.css` 中颜色应按页面语义保留。

### 页面根

```css
.command-dialog {
    -fx-background-color: -fx-bg-primary;
    -fx-border-color: -fx-border-default;
}
```

这是弹窗主画布，使用 `primary` 合理。

### 标题栏

```css
.cmd-header {
    -fx-background-color: -fx-bg-tertiary;
    -fx-border-color: -fx-border-default;
}
```

标题栏是结构强调层，使用 `tertiary`。

### 树区域

```css
.cmd-tree {
    -fx-background-color: -fx-bg-primary;
    -fx-border-color: -fx-border-default;
}
```

树区域是数据区，使用 `primary`。右侧分隔优先使用 border。

### 工具栏

```css
.cmd-toolbar {
    -fx-background-color: -fx-bg-secondary;
    -fx-border-color: -fx-border-default;
}
```

工具栏承载按钮、搜索框等操作控件，使用 `secondary`。

### 组合搜索框

```css
.cmd-search-input-wrap {
    -fx-border-width: 1px;
}

.cmd-search-input {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
}
```

`cmd-search-input-wrap` 使用 `hbox-input` 提供输入框外观。内部 `TextField` 透明是结构需要，避免双层输入框背景和边框。

这种透明覆盖可以保留在页面 CSS，因为它不是主题颜色选择，而是组合控件结构的一部分。

### TreeView 覆盖

```css
.cmd-tree-view {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
}
```

这表示 `TreeView` 视觉上并入 `.cmd-tree` 容器。如果确实需要容器统一承担背景和边框，可以保留。

如果没有这种结构需求，应删除该规则，让 `TreeView` 使用 `theme-variables.css` 默认皮肤。

### 图标

```css
.cmd-search-icon {
    -fx-icon-color: -fx-text-muted;
}

.cmd-icon-folder {
    -fx-icon-color: -fx-icon-folder;
}

.cmd-icon-command {
    -fx-icon-color: -fx-icon-connection-linux;
}
```

这些是业务语义图标颜色，可以先保留在页面 CSS。若多个页面重复，再抽成全局语义图标类。

## 不推荐写法

### 不要按布局组件设置语义背景

不推荐：

```css
HBox {
    -fx-background-color: -fx-bg-secondary;
}
```

`HBox` 可能是 toolbar，也可能只是普通布局容器。按组件类型设置背景会误伤。

### 不要在页面 CSS 重复原生控件默认色

不推荐：

```css
.file-table {
    -fx-background-color: -fx-bg-primary;
}

.file-table .table-row-cell:hover {
    -fx-background-color: -fx-bg-hover;
}
```

这些应该由全局 TableView 皮肤控制。

### 不要写具体颜色值

不推荐：

```css
.some-node {
    -fx-background-color: #161b22;
}
```

应该写 token：

```css
.some-node {
    -fx-background-color: -fx-bg-secondary;
}
```

### 不要让页面 CSS 污染全局

不推荐：

```css
.table-view .table-row-cell:hover {
    -fx-background-color: transparent;
}
```

如果只想影响左侧面板的小表格，应限制作用域：

```css
.process-table .table-row-cell:hover,
.disk-table .table-row-cell:hover,
.user-table .table-row-cell:hover {
    -fx-background-color: transparent;
}
```

## 新增样式时的流程

1. 判断这是主题常量、组件默认皮肤，还是页面布局。

2. 如果是颜色值本身，放到 `theme-dark.css` 和 `theme-light.css`。

3. 如果是原生控件默认外观，放到 `theme-variables.css`。

4. 如果是页面结构、间距、尺寸、对齐，放到对应页面 CSS。

5. 如果页面 CSS 中必须写颜色，确认它是页面语义层级，而不是控件默认色。

6. 如果同样的颜色规则在三个以上页面重复，考虑抽成 `theme-variables.css` 中的通用语义类。

## 快速归类表

| 样式内容 | 放置位置 |
| --- | --- |
| `-fx-bg-primary: #...` | `theme-dark.css` / `theme-light.css` |
| `.table-view .table-row-cell:hover` | `theme-variables.css` |
| `.button-primary` | `theme-variables.css` |
| `.button-cancel` | `theme-variables.css` |
| `.spinner .increment-arrow-button` | `theme-variables.css` |
| `.cmd-toolbar { -fx-padding: ... }` | `command-dialog.css` |
| `.cmd-toolbar { -fx-background-color: -fx-bg-secondary }` | `command-dialog.css`，因为它是页面语义层级 |
| `.cmd-search-input { -fx-background-color: transparent }` | `command-dialog.css`，因为它是组合控件内部结构 |
| `.file-table .table-row-cell:hover` | 通常不写，交给 `theme-variables.css` |
| `-fx-terminal-bg` | `theme-dark.css` / `theme-light.css` |
| `-ys-terminal-background` 映射 | `terminal.css` |

## 核心原则

primary 是画布，secondary 是操作和承载层，tertiary 是结构强调层，transparent 是布局默认值。

主题文件定义值，组件文件定义默认皮肤，页面文件定义结构和页面语义。页面 CSS 不重复控件皮肤，组件皮肤不写页面布局，主题常量不直接绑定业务页面。
