# 部署手册

## 已部署实例

- **主机**: `43.128.109.91` (Ubuntu 22.04)
- **应用端口**: `8801` (Spring Boot 内嵌 Tomcat 同时服务 API 与静态页)
- **数据库**: 本机 MySQL 8, 库 `zalo_bg`, 账号 `zalo / ZaloBg@2026`
- **systemd 服务**: `zalo-bg.service`
- **日志**: `/opt/zalo-bg/logs/app.log`
- **jar 位置**: `/opt/zalo-bg/zalo-bg.jar`
- **对外域名**: `zalo.qxh77.com` (nginx :80 反代 → 127.0.0.1:8801, HTTPS 由 Cloudflare 终结)
- **后台首页**: https://zalo.qxh77.com/
- **Swagger**: https://zalo.qxh77.com/swagger-ui.html
- **静态文件镜像**: https://zalo.qxh77.com/files/*  (源于 GitHub 的 `zalo-website` 仓库, cron 自动同步, 见下)

## 前端 JS 自动同步 (zalo-website 私有仓库 → /opt/zalo-bg/files/)

为了向 Zalo 插件或第三方提供 `chunk-f6588e50.efaa59d3.js` 的最新版本,使用一个 GitHub
fine-grained PAT + cron 每 2 分钟拉一次, 有变动才覆盖目标文件, 无变动不动 mtime。

```
组件                             路径                                     说明
------------------------------  ---------------------------------------  ------------------------------------
PAT (contents:read, 只读)        /opt/zalo-bg/bin/.pat                    0600, ubuntu:ubuntu, 只存单行 token
同步脚本                         /opt/zalo-bg/bin/sync-js.sh              源码见 scripts/sync-js.sh
本地工作区                       /opt/zalo-bg/src/zalo-website/           git clone --depth 1
输出目录 (nginx 暴露)             /opt/zalo-bg/files/                      alias in /etc/nginx/sites-available/zalo-bg
cron 条目 (ubuntu 用户)           crontab -l                               */2 * * * * /opt/zalo-bg/bin/sync-js.sh ...
同步日志                         /var/log/zalo-bg-sync.log                只记录 UPDATED/ERROR/FATAL 行
跟踪的分支                       $BRANCH 环境变量 (默认 feature 分支)      可在 sync-js.sh 顶部或 cron 行里覆盖
```

**如何切换到其它分支 / 文件**
```bash
# 暂时切分支
BRANCH=main /opt/zalo-bg/bin/sync-js.sh

# 永久切分支 — 改脚本顶部的 BRANCH 默认值
sudoedit /opt/zalo-bg/bin/sync-js.sh
```

**如何轮换 PAT**
```bash
umask 177
printf '%s' '<新的 github_pat_...>' > /opt/zalo-bg/bin/.pat
# 下次 cron 跑时自动生效,无需重启
```

**手动触发一次同步**
```bash
/opt/zalo-bg/bin/sync-js.sh && tail -5 /var/log/zalo-bg-sync.log
```

## 运维常用命令

```bash
# 查看服务状态
sudo systemctl status zalo-bg

# 重启
sudo systemctl restart zalo-bg

# 查看实时日志
tail -f /opt/zalo-bg/logs/app.log

# 直连数据库
mysql -uzalo -pZaloBg@2026 zalo_bg
```

## 配置(环境变量)

`systemd` 单元通过环境变量注入配置, 见 `/etc/systemd/system/zalo-bg.service`:

| 变量 | 说明 | 默认 |
|---|---|---|
| `DB_HOST` | MySQL 主机 | `127.0.0.1` |
| `DB_PORT` | MySQL 端口 | `3306` |
| `DB_NAME` | 数据库名 | `zalo_bg` |
| `DB_USER` | 用户名 | `zalo` |
| `DB_PASSWORD` | 密码 | `ZaloBg@2026` |
| `JWT_SECRET` | JWT 签名密钥 | (随机 32 字节 hex) |
| `COLLECT_TOKEN` | 采集接口校验 token | (随机 16 字节 hex) |
| `ADMIN_USER` | 初始管理员用户名 | `admin` |
| `ADMIN_PASSWORD` | 初始管理员密码 | (随机 12 位) |

修改环境变量后:
```bash
sudo systemctl daemon-reload
sudo systemctl restart zalo-bg
```

## 从源码重新构建并部署

```bash
# 在你本地 clone 仓库
git clone https://github.com/AlexFrankHu/zalo-bg.git
cd zalo-bg
mvn -DskipTests clean package
# 产物: target/zalo-bg.jar

# 上传到服务器
scp target/zalo-bg.jar ubuntu@43.128.109.91:/opt/zalo-bg/zalo-bg.jar
ssh ubuntu@43.128.109.91 "sudo systemctl restart zalo-bg"
```

## 初次安装(从零搭建同样的服务器)

```bash
# 1. 装依赖
sudo apt-get update
sudo apt-get install -y openjdk-17-jre-headless mysql-server

# 2. 建库建用户
sudo mysql -uroot <<SQL
CREATE DATABASE zalo_bg DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'zalo'@'localhost' IDENTIFIED BY 'ZaloBg@2026';
GRANT ALL PRIVILEGES ON zalo_bg.* TO 'zalo'@'localhost';
FLUSH PRIVILEGES;
SQL

# 3. 放置 jar
sudo mkdir -p /opt/zalo-bg/logs
sudo chown -R ubuntu:ubuntu /opt/zalo-bg

# 4. systemd 服务
sudo tee /etc/systemd/system/zalo-bg.service <<'EOF'
[Unit]
Description=Zalo Data Admin Backend
After=network.target mysql.service

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/zalo-bg
Environment=DB_PASSWORD=ZaloBg@2026
Environment=JWT_SECRET=<换成你自己的 32 字节随机 hex>
Environment=COLLECT_TOKEN=<换成你自己的随机 hex>
Environment=ADMIN_PASSWORD=<换成强密码>
ExecStart=/usr/bin/java -Xms256m -Xmx1g -jar /opt/zalo-bg/zalo-bg.jar
Restart=on-failure
StandardOutput=append:/opt/zalo-bg/logs/app.log
StandardError=append:/opt/zalo-bg/logs/app.log

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now zalo-bg
```

首次启动时, 应用会自动:
1. 在 MySQL 中建表 (见 `schema.sql`);
2. 若 `admin_user` 表里没有 `admin` 记录, 自动写入一条 (密码来自 `ADMIN_PASSWORD` 环境变量, BCrypt 存储)。
