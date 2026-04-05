# LargerInventory

<img src="screenshots/icon.png" width="128" />


[中文](#中文) | [English](#english)

---
<a name="中文"></a>

## 中文

一个 Minecraft Spigot 插件，通过分页系统扩展玩家背包容量。

### 功能特性

- **分页背包** - 将玩家背包扩展为最多 99 页，远超原版 27 格限制
- **翻页按钮** - 直观的上一页/下一页按钮，支持自定义位置、外观和音效
- **自定义纹理** - 支持 CustomModelData 和资源包，可自定义按钮图标
- **跨页拾取** - 当背包页满时自动将物品存放到其他页，无需手动翻页
- **跨页死亡掉落** - 死亡时所有页面的物品都会掉落（与游戏规则 keepInventory 同步）
- **页面命名** - 为背包页添加书签名称，方便管理
- **快速跳页** - `/li goto <页码>` 直接跳转到指定页
- **权限页数限制** - 通过权限节点控制不同玩家的最大页数
- **备份恢复** - 支持创建、列出、恢复和删除玩家背包备份
- **PlaceholderAPI** - 提供占位符变量，集成记分板和 HUD
- **资源包推送** - 配置 URL 后自动推送资源包给进服玩家
- **MySQL 支持** - 支持 SQLite 和 MySQL 双数据库
- **热更新** - 支持 `/li reload` 热更新配置，无需重启服务器
- **自动保存** - 定时将数据持久化到数据库
- **交接容器** - 页数缩减时物品自动回填，放不下的转入交接容器
- **懒加载** - 仅加载当前页数据，减少内存占用
- **LRU 缓存** - 内存中仅保留最近访问的 6 页，优化性能
- **国际化支持** - 支持中文和英文，可在配置中切换

### 环境要求

- Java 21+
- Spigot/Paper 1.21+

### 安装

1. 从 [Releases](https://gitee.com/sh-xiaoxiu/spigot-larger-inventory/releases) 下载最新版本的 JAR 文件
2. 放入服务器的 `plugins` 目录
3. 重启服务器或使用插件管理器加载

### 配置

配置文件位于 `plugins/LargerInventory/config.yml`：

```yaml
# 语言设置 (zh_CN / en_US)
language: zh_CN

# 按钮设置
buttons:
  prev-page-slot: 27    # 上一页按钮槽位 (9-35)
  next-page-slot: 35    # 下一页按钮槽位 (9-35)
  prev-material: "ARROW"
  next-material: "ARROW"
  page-turn-sound: "UI_BUTTON_CLICK"  # 翻页音效 (NONE 禁用)
  page-turn-volume: 1.0
  page-turn-pitch: 1.0
  prev-custom-model-data: 0   # 自定义模型数据 (0 = 禁用)
  next-custom-model-data: 0
  disabled-custom-model-data: 0

# 分页限制 (0 = 使用最大限制 99 页)
limits:
  max-pages: 0

# 功能开关
features:
  cross-page-pickup: true        # 跨页拾取
  cross-page-death-drop: true    # 跨页死亡掉落

# 资源包设置
resource-pack:
  url: ""           # 资源包 URL (留空禁用)
  required: false   # 是否强制使用
  prompt: ""        # 提示消息

# 数据库设置
database:
  type: sqlite      # sqlite / mysql
  mysql:
    host: localhost
    port: 3306
    database: largerinventory
    username: root
    password: ""
    pool-size: 10

# 数据保护
data:
  backup-retention-days: 7
  auto-save-interval-seconds: 300
```

### 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/li info [player]` | 查看玩家背包信息 | 玩家/管理员 |
| `/li goto <页码>` | 跳转到指定页 | `largerinventory.player.goto` |
| `/li name [名称]` | 为当前页命名（留空清除） | `largerinventory.player.name` |
| `/li opencontainer [player]` | 打开交接容器 | 玩家/管理员 |
| `/li backup <create\|list\|restore\|delete>` | 备份管理 | `largerinventory.admin.backup` |
| `/li forcereset` | 强制重置超出限制的背包 | `largerinventory.admin.forcereset` |
| `/li bypass` | 创造模式下临时解除按钮槽拦截 | `largerinventory.admin.bypass` |
| `/li reload` | 重载配置文件 | `largerinventory.admin.reload` |

#### Bypass 模式

创造模式下使用 `/li bypass` 可临时解除按钮槽拦截，允许管理员自由编辑背包中的按钮槽位。

- **开启条件**：必须处于创造模式
- **使用方式**：输入 `/li bypass` 开启，再次输入关闭
- **关闭要求**：关闭前需先清空按钮槽中的物品
- **自动退出**：切换出创造模式时自动关闭 bypass 模式

### 权限

| 权限节点 | 说明 | 默认 |
|----------|------|------|
| `largerinventory.player.opencontainer` | 打开自己的交接容器 | 玩家 |
| `largerinventory.player.info` | 查看自己的背包信息 | 玩家 |
| `largerinventory.player.goto` | 快速跳转到指定页 | 玩家 |
| `largerinventory.player.name` | 为页面命名 | 玩家 |
| `largerinventory.pages.<N>` | 限制最大页数为 N | 无 |
| `largerinventory.admin.forcereset` | 强制重置背包 | OP |
| `largerinventory.admin.opencontainer` | 打开任意玩家交接容器 | OP |
| `largerinventory.admin.reload` | 重载配置 | OP |
| `largerinventory.admin.info` | 查看任意玩家背包信息 | OP |
| `largerinventory.admin.bypass` | 创造模式下解除按钮槽拦截 | OP |
| `largerinventory.admin.backup` | 备份和恢复玩家背包 | OP |
| `largerinventory.admin.*` | 所有管理员权限 | OP |

### PlaceholderAPI 变量

| 变量 | 说明 |
|------|------|
| `%largerinventory_current_page%` | 当前页码 |
| `%largerinventory_max_page%` | 已使用的最大页码 |
| `%largerinventory_effective_max_pages%` | 有效页数限制 |
| `%largerinventory_page_name%` | 当前页名称 |

### 资源包

插件首次启动时会在 `plugins/LargerInventory/resource-pack/` 目录生成示例资源包模板。

使用方法：
1. 修改模板中的纹理图片（16x16 或 32x32 PNG）
2. 将 `resource-pack/` 目录打包为 ZIP 并上传到 Web 服务器
3. 在 `config.yml` 中设置 `resource-pack.url` 和 `buttons.*-custom-model-data`（推荐值：1001/1002/1003）
4. `/li reload` 后重进服务器

### 数据存储

- **SQLite**：数据保存在 `plugins/LargerInventory/data.db`
- **MySQL**：在 `config.yml` 中配置 `database.type: mysql` 及连接信息

### 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/` 目录。

---

<a name="english"></a>

## English

A Minecraft Spigot plugin that extends player inventory capacity through a paging system.

### Features

- **Paged Inventory** - Expand player inventory up to 99 pages, far beyond the vanilla 27-slot limit
- **Page Navigation Buttons** - Intuitive previous/next page buttons with customizable position, appearance and sound
- **Custom Textures** - Support CustomModelData and resource packs for custom button icons
- **Cross-page Pickup** - Automatically store items to other pages when current page is full
- **Cross-page Death Drop** - Items from all pages drop on death (syncs with keepInventory game rule)
- **Page Naming** - Add bookmark names to inventory pages for easy management
- **Quick Jump** - `/li goto <page>` to jump directly to a specific page
- **Permission-based Page Limits** - Control max pages per player via permission nodes
- **Backup & Restore** - Create, list, restore and delete player inventory backups
- **PlaceholderAPI** - Provides placeholder variables for scoreboards and HUDs
- **Resource Pack Push** - Automatically send resource pack to players on join
- **MySQL Support** - Supports both SQLite and MySQL
- **Hot Reload** - Support `/li reload` to update configuration without server restart
- **Auto-save** - Periodically persist data to database
- **Handover Container** - Items auto-redistribute when page limit decreases, overflow goes to handover container
- **Lazy Loading** - Only load current page data to reduce memory usage
- **LRU Cache** - Keep only the 6 most recently accessed pages in memory for optimized performance
- **i18n Support** - Supports Chinese and English, switchable via configuration

### Requirements

- Java 21+
- Spigot/Paper 1.21+

### Installation

1. Download the latest JAR file from [Releases](https://gitee.com/sh-xiaoxiu/spigot-larger-inventory/releases)
2. Place it in the server's `plugins` directory
3. Restart the server or use a plugin manager to load it

### Configuration

Configuration file located at `plugins/LargerInventory/config.yml`:

```yaml
# Language settings (zh_CN / en_US)
language: en_US

# Button settings
buttons:
  prev-page-slot: 27    # Previous page button slot (9-35)
  next-page-slot: 35    # Next page button slot (9-35)
  prev-material: "ARROW"
  next-material: "ARROW"
  page-turn-sound: "UI_BUTTON_CLICK"  # Page turn sound (NONE to disable)
  page-turn-volume: 1.0
  page-turn-pitch: 1.0
  prev-custom-model-data: 0   # Custom model data (0 = disabled)
  next-custom-model-data: 0
  disabled-custom-model-data: 0

# Page limits (0 = use maximum limit of 99 pages)
limits:
  max-pages: 0

# Feature toggles
features:
  cross-page-pickup: true        # Cross-page pickup
  cross-page-death-drop: true    # Cross-page death drop

# Resource pack settings
resource-pack:
  url: ""           # Resource pack URL (empty to disable)
  required: false   # Force players to use
  prompt: ""        # Prompt message

# Database settings
database:
  type: sqlite      # sqlite / mysql
  mysql:
    host: localhost
    port: 3306
    database: largerinventory
    username: root
    password: ""
    pool-size: 10

# Data protection
data:
  backup-retention-days: 7
  auto-save-interval-seconds: 300
```

### Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/li info [player]` | View player inventory info | Player/Admin |
| `/li goto <page>` | Jump to a specific page | `largerinventory.player.goto` |
| `/li name [name]` | Name current page (empty to clear) | `largerinventory.player.name` |
| `/li opencontainer [player]` | Open handover container | Player/Admin |
| `/li backup <create\|list\|restore\|delete>` | Backup management | `largerinventory.admin.backup` |
| `/li forcereset` | Force reset inventory exceeding limits | `largerinventory.admin.forcereset` |
| `/li bypass` | Temporarily bypass button slot protection in creative mode | `largerinventory.admin.bypass` |
| `/li reload` | Reload configuration | `largerinventory.admin.reload` |

#### Bypass Mode

Use `/li bypass` in creative mode to temporarily bypass button slot protection, allowing admins to freely edit button slots in inventory.

- **Activation**: Must be in creative mode
- **Usage**: Type `/li bypass` to enable, type again to disable
- **Disable Requirement**: Clear items from button slots before disabling
- **Auto-exit**: Automatically disables when switching out of creative mode

### Permissions

| Permission Node | Description | Default |
|-----------------|-------------|---------|
| `largerinventory.player.opencontainer` | Open own handover container | Player |
| `largerinventory.player.info` | View own inventory info | Player |
| `largerinventory.player.goto` | Quick jump to specific page | Player |
| `largerinventory.player.name` | Name inventory pages | Player |
| `largerinventory.pages.<N>` | Limit max pages to N | None |
| `largerinventory.admin.forcereset` | Force reset inventory | OP |
| `largerinventory.admin.opencontainer` | Open any player's handover container | OP |
| `largerinventory.admin.reload` | Reload configuration | OP |
| `largerinventory.admin.info` | View any player's inventory info | OP |
| `largerinventory.admin.bypass` | Bypass button slot protection in creative | OP |
| `largerinventory.admin.backup` | Backup and restore player inventory | OP |
| `largerinventory.admin.*` | All admin permissions | OP |

### PlaceholderAPI Variables

| Variable | Description |
|----------|-------------|
| `%largerinventory_current_page%` | Current page number |
| `%largerinventory_max_page%` | Maximum used page number |
| `%largerinventory_effective_max_pages%` | Effective page limit |
| `%largerinventory_page_name%` | Current page name |

### Resource Pack

The plugin generates a sample resource pack template in `plugins/LargerInventory/resource-pack/` on first launch.

Usage:
1. Replace texture images in the template (16x16 or 32x32 PNG)
2. Zip the `resource-pack/` directory and upload to a web server
3. Set `resource-pack.url` and `buttons.*-custom-model-data` in `config.yml` (recommended: 1001/1002/1003)
4. `/li reload` and rejoin the server

### Data Storage

- **SQLite**: Data saved in `plugins/LargerInventory/data.db`
- **MySQL**: Configure `database.type: mysql` and connection info in `config.yml`

### Building

```bash
./gradlew build
```

The build artifact will be located in the `build/libs/` directory.

---

## Screenshots / 游戏截图

### Paged Inventory / 分页背包

![Paged Inventory](screenshots/2026-03-27_22-31-41.png)
![Page Navigation](screenshots/2026-03-27_22-32-08.png)
![Inventory View](screenshots/2026-03-27_22-32-18.png)

### Page Transition / 翻页演示

![Page Transition Demo](screenshots/FE2F9BE96248ADBDEBC59EA3CD2D3C84.gif)

### Handover Container / 交接容器

![Handover Container 1](screenshots/2026-03-27_22-37-06.png)
![Handover Container 2](screenshots/2026-03-27_22-38-06.png)

---

## Author / 作者

XiaoXiu - [www.xiuxius.cn](https://www.xiuxius.cn)

## License / 许可证

MIT License
