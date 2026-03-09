# SoulWarehouse

一个基于 [TabooLib](https://github.com/TabooLib/taboolib) 的 Minecraft Bukkit 多页仓库插件，为玩家提供可扩展的虚拟存储空间。

## 功能

- **多页仓库** — 每位玩家拥有独立的多页仓库（默认 3 页，最多 10 页），通过权限节点按需解锁
- **真实物品交互** — 仓库页基于 `StorableChest`，支持拖放、Shift 点击、堆叠等原生背包操作
- **自动拾取入仓** — 拾取地面掉落物时自动存入仓库，优先合并已有堆叠，溢出部分回到背包
- **页面备注** — 玩家可为每页设置备注文本，方便分类管理
- **管理员查看** — 管理员可只读查看任意玩家（含离线）的仓库内容
- **物品过滤** — 支持材质黑名单和潜影盒开关
- **多数据库支持** — MySQL / MariaDB / PostgreSQL / SQLite
- **完全可自定义 UI** — 标题、行数、槽位布局、按钮材质和描述均可通过 YAML 配置

## 环境要求

- Java 8+
- Minecraft 1.12+（Bukkit / Spigot / Paper）
- TabooLib 6.2

## 构建

```bash
./gradlew build
```

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/warehouse` | 打开仓库主页 | `soulwarehouse.use` |
| `/warehouse open <页码>` | 直接打开自己的某一页 | `soulwarehouse.use` |
| `/warehouse open <玩家> [页码]` | 只读查看其他玩家仓库 | `soulwarehouse.admin.open` |
| `/warehouse reload` | 重载配置 | `soulwarehouse.admin.reload` |
| `/warehouse saveall` | 强制保存所有缓存数据 | `soulwarehouse.admin.saveall` |

命令别名：`/sw`

## 权限

| 权限节点 | 说明 |
|---|---|
| `soulwarehouse.use` | 使用仓库基础功能 |
| `soulwarehouse.page.*` | 解锁全部页面 |
| `soulwarehouse.page.<N>` | 解锁到第 N 页 |
| `soulwarehouse.admin.open` | 查看其他玩家仓库 |
| `soulwarehouse.admin.reload` | 重载配置 |
| `soulwarehouse.admin.saveall` | 强制保存 |

## 配置

### config.yml

```yaml
database:
  type: mysql          # mysql / mariadb / postgresql / sqlite
  host: localhost
  port: 3306
  user: root
  password: ''
  database: minecraft
  file: warehouse.db   # SQLite 文件名
  table-prefix: sw_
  use-ssl: false

warehouse:
  default-pages: 3     # 玩家默认页数
  max-pages: 10        # 最大页数 (1-10)
  page-size: 45        # 每页槽位数 (9-45)
  allow-shulker-box: true
  blacklist:
    - BEDROCK

save:
  auto-save: true
  auto-save-interval: 300  # 自动保存间隔（秒）
  save-on-close: true      # 关闭菜单时保存
  save-on-quit: true       # 退出时保存

pickup:
  auto-store-drops: true   # 拾取自动入仓
```

### UI 配置

| 文件 | 用途 |
|---|---|
| `ui/home.yml` | 玩家仓库主页 |
| `ui/page.yml` | 玩家仓库页 |
| `ui/inspect-home.yml` | 管理员查看主页 |
| `ui/inspect-page.yml` | 管理员查看仓库页 |

每个 UI 配置文件支持自定义标题、行数、槽位布局、按钮位置和物品外观。

## 数据存储

插件通过 TabooLib PtcObject 持久化框架管理数据，包含三张表：

| 表名 | 内容 |
|---|---|
| `{prefix}player_profile` | 玩家档案（UUID、名称、页数、时间戳） |
| `{prefix}player_warehouse_page` | 仓库页内容（序列化物品数据） |
| `{prefix}player_warehouse_remark` | 页面备注 |
