# zalo-bg — Zalo 数据采集管理后台

围绕 Zalo WebSocket 抓取数据做的 **Java 管理后台**, 包含:
1. **采集接口**: 把客户端抓到的 Zalo WS `code=1/7/17` 原始 JSON 直接推进来, 入库。
2. **查询后台**: Vue3 + Element Plus 的 SPA, 按条件分页查询账号 / 好友 / 聊天记录 (**只读**)。
3. **API 文档**: `doc/api.md` + Swagger UI (`/swagger-ui.html`)。

## 技术栈

- Java 17 / Spring Boot 3.3.x
- MyBatis-Plus 3.5.x
- MySQL 8
- Spring Security + JWT
- springdoc-openapi 2.x (Swagger UI)
- 前端: Vue 3 + Element Plus (CDN 单文件, 免构建)

## 目录

```
zalo-bg/
├── doc/
│   ├── record.txt       # 需求文档 (原始)
│   ├── api.md           # 接口文档
│   └── deployment.md    # 部署手册
├── src/main/
│   ├── java/com/zalobg/
│   │   ├── ZaloBgApplication.java
│   │   ├── auth/        # JWT
│   │   ├── common/      # R / 异常
│   │   ├── config/      # MyBatis-Plus / Security / 启动初始化
│   │   ├── controller/  # REST Controller
│   │   ├── entity/      # 实体
│   │   ├── mapper/      # MyBatis-Plus Mapper
│   │   └── service/
│   └── resources/
│       ├── application.yml
│       ├── schema.sql   # 首次启动自动建表
│       └── static/      # SPA 前端 (index.html / css / js)
└── pom.xml
```

## 快速开始 (本地)

```bash
mvn -DskipTests clean package
java -jar target/zalo-bg.jar \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/zalo_bg?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai' \
  --spring.datasource.username=root \
  --spring.datasource.password=root
```
访问 http://localhost:8801/ (默认账号 `admin` / `admin123`, 正式部署请用 `ADMIN_PASSWORD` 环境变量覆盖)。

## 接口概览

| Method | Path | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 无 | 管理员登录 |
| POST | `/api/collect/accounts` | `X-Collect-Token` | 采集 WS code=1 账号列表 |
| POST | `/api/collect/friends`  | `X-Collect-Token` | 采集 WS code=7 好友列表 |
| POST | `/api/collect/messages` | `X-Collect-Token` | 采集 WS code=17 聊天记录 |
| GET  | `/api/admin/accounts`   | Bearer | 查询账号 (分页+过滤) |
| GET  | `/api/admin/accounts/{id}` | Bearer | 账号详情 |
| GET  | `/api/admin/friends`    | Bearer | 查询好友 (分页+过滤) |
| GET  | `/api/admin/messages`   | Bearer | 查询聊天记录 (分页+过滤) |
| GET  | `/api/health`           | 无 | 健康检查 |

详见 `doc/api.md`。

## 采集接口鉴权

采集接口使用独立 token (与后台登录密码隔离), 客户端每次请求都要带 Header:
```
X-Collect-Token: <COLLECT_TOKEN>
```
`COLLECT_TOKEN` 由部署方生成后通过环境变量 `COLLECT_TOKEN` 注入。

## 部署

线上已部署在 `43.128.109.91:8801`, 详细见 `doc/deployment.md`。

## 许可

Internal use only.
