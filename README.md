# LargerInventory

一个 Minecraft Spigot 插件，通过分页系统扩展玩家背包容量。



![2026-03-27_22-31-41.png](screenshots/2026-03-27_22-31-41.png)
![2026-03-27_22-32-08.png](screenshots/2026-03-27_22-32-08.png)
![2026-03-27_22-32-18.png](screenshots/2026-03-27_22-32-18.png)
## 功能特性

- **分页背包** - 将玩家背包扩展为最多 99 页，远超原版 27 格限制
- **翻页按钮** - 直观的上一页/下一页按钮，支持自定义位置和外观
- **自动保存** - 定时将数据持久化到 SQLite 数据库
- **交接容器** - 页数满时物品自动转入交接容器，防止丢失
- **懒加载** - 仅加载当前页数据，减少内存占用
- **LRU 缓存** - 内存中仅保留最近访问的 6 页，优化性能

![FE2F9BE96248ADBDEBC59EA3CD2D3C84.gif](screenshots/FE2F9BE96248ADBDEBC59EA3CD2D3C84.gif)



## 交接系统

![2026-03-27_22-37-06.png](screenshots/2026-03-27_22-37-06.png)
![2026-03-27_22-38-06.png](screenshots/2026-03-27_22-38-06.png)


## 环境要求

- Java 21+
- Spigot/Paper 1.21+

## 安装

1. 从 [Releases](https://gitee.com/sh-xiaoxiu/spigot-larger-inventory/releases) 下载最新版本的 JAR 文件
2. 放入服务器的 `plugins` 目录
3. 重启服务器或使用插件管理器加载

## 配置

配置文件位于 `plugins/LargerInventory/config.yml`：

```yaml
# 翻页按钮设置
buttons:
  prev-page:
    slot: 27          # 上一页按钮槽位 (0-35)
    material: ARROW   # 按钮材质
    name: "&a上一页"
  next-page:
    slot: 35          # 下一页按钮槽位 (0-35)
    material: ARROW
    name: "&c下一页"

# 功能设置
settings:
  max-pages: 99       # 最大页数限制
  auto-save-interval: 300  # 自动保存间隔（秒）
```

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/li info [player]` | 查看玩家背包信息 | `largerinventory.admin` |
| `/li forcereset` | 强制重置超出限制的背包 | `largerinventory.admin` |
| `/li opencontainer [player]` | 打开交接容器 | `largerinventory.admin` |
| `/li bypass` | 创造模式下临时解除按钮槽拦截 | `largerinventory.admin.bypass` |
| `/li reload` | 重载配置文件 | `largerinventory.admin` |

### Bypass 模式

创造模式下使用 `/li bypass` 可临时解除按钮槽拦截，允许管理员自由编辑背包中的按钮槽位。

- **开启条件**：必须处于创造模式
- **使用方式**：输入 `/li bypass` 开启，再次输入关闭
- **关闭要求**：关闭前需先清空按钮槽中的物品
- **自动退出**：切换出创造模式时自动关闭 bypass 模式 |

## 权限

| 权限节点 | 说明 | 默认 |
|----------|------|------|
| `largerinventory.use` | 使用扩展背包功能 | 玩家 |
| `largerinventory.player.opencontainer` | 打开自己的交接容器 | 玩家 |
| `largerinventory.player.info` | 查看自己的背包信息 | 玩家 |
| `largerinventory.admin.forcereset` | 强制重置背包 | OP |
| `largerinventory.admin.opencontainer` | 打开任意玩家交接容器 | OP |
| `largerinventory.admin.reload` | 重载配置 | OP |
| `largerinventory.admin.info` | 查看任意玩家背包信息 | OP |
| `largerinventory.admin.bypass` | 创造模式下解除按钮槽拦截 | OP |
| `largerinventory.admin.*` | 所有管理员权限 | OP |

### 数据存储

```bash
./gradlew build
```

构建产物位于 `build/libs/` 目录。

## 作者

XiaoXiu - [www.xiuxius.cn](https://www.xiuxius.cn)

## 许可证

MIT License
