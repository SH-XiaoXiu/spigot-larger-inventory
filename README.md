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

1. 下载最新版本的 JAR 文件
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
| `/li reload` | 重载配置文件 | `largerinventory.admin` |

## 权限

| 权限节点 | 说明 | 默认 |
|----------|------|------|
| `largerinventory.use` | 使用扩展背包功能 | 玩家 |
| `largerinventory.admin` | 管理员命令权限 | OP |

### 数据存储

```bash
./gradlew build
```

构建产物位于 `build/libs/` 目录。

## 作者

XiaoXiu - [www.xiuxius.cn](https://www.xiuxius.cn)

## 许可证

MIT License
