# Zalo 管理后台接口文档

**Base URL**: `http://43.128.109.91:8801`
**在线交互式文档**: `http://43.128.109.91:8801/swagger-ui.html`
**OpenAPI JSON**: `http://43.128.109.91:8801/v3/api-docs`

所有业务接口统一返回结构:
```json
{ "code": 0, "msg": "ok", "data": <payload> }
```
- `code == 0` 表示成功
- `code == 401` 未登录/登录失效(采集接口为 collect token 不对)
- `code == 400` 参数校验失败
- `code == 500` 服务器内部错误

---

## 1. 认证

### 1.1 POST `/api/auth/login`

管理员登录, 返回 JWT。

**请求头**: `Content-Type: application/json`

**请求体**:
```json
{ "username": "admin", "password": "xxxxxxxx" }
```

**响应**:
```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "token": "eyJhbGciOi...",
    "username": "admin",
    "nickName": "Administrator"
  }
}
```

**后续请求**需在 Header 里带:
```
Authorization: Bearer <token>
```
Token 默认有效期 24 小时(可通过 `zalo-bg.auth.jwt-ttl-hours` 配置)。

---

## 2. 采集接口 (客户端推送 Zalo WS 原始数据入库)

三个采集接口使用**独立鉴权**, 所有请求必须带:
```
X-Collect-Token: <COLLECT_TOKEN>
```
`COLLECT_TOKEN` 与登录密码不同, 由部署方配置 (环境变量 `COLLECT_TOKEN`)。

所有采集接口接受三种等价的请求体:
1. 完整的 WS 响应体: `{"code":1,"data":[...]}`
2. 只有 data 数组: `[{...}, {...}]`
3. 对于 `code=17`: 还可以是 `{"list":[...]}`

采集是**幂等**的 (upsert): 同一条记录重复推送不会重复入库, 只会更新关键字段。

### 2.1 POST `/api/collect/accounts`

采集 WS `code=1` 下发的账号列表。

**唯一键**: `data[i].ID` (映射为 `zalo_account.zalo_id`)

**字段映射**:
| 源字段 (WS code=1 data[i]) | 表字段 (zalo_account) |
|---|---|
| `ID` | `zalo_id` (唯一键) |
| `account` | `account` |
| `nickName` | `nick_name` |
| `region` | `region` |
| `password` | `password` |
| `sex` | `sex` |
| `headimg` | `headimg` |
| `online` | `online` |
| `accountStatus` | `account_status` |
| `remark` | `remark` |
| `proxyip` | `proxy_ip` |
| `proxyId` | `proxy_id` |
| `friendsum` | `friends_sum` |
| `groupsum` | `group_sum` |
| `msgSum` | `msg_sum` |
| `groupId` | `group_id` |
| `group.groupName` | `group_name` |
| `deptId` | `dept_id` |
| `bindId` | `bind_id` |
| `sdbo` | `sdbo` |
| `wxid` | `wxid` |
| `isshop` | `is_shop` |
| `realName` | `real_name` |
| `createBy` | `create_by` |
| `CreatedAt` | `gmt_create` |
| `UpdatedAt` | `gmt_update` |
| *整条 JSON* | `raw_json` |

**示例**:
```bash
curl -X POST http://43.128.109.91:8801/api/collect/accounts \
  -H 'X-Collect-Token: <COLLECT_TOKEN>' \
  -H 'Content-Type: application/json' \
  --data-binary @code1_response.json
```

**返回**:
```json
{ "code": 0, "msg": "ok", "data": { "upserted": 2 } }
```

### 2.2 POST `/api/collect/friends`

采集 WS `code=7` 下发的好友列表。每条自带 `accountId`, 因此无需额外传参指定归属账号。

**唯一键**: `(accountId, userId)` 复合唯一

**字段映射**:
| 源字段 (WS code=7 data[i]) | 表字段 (zalo_friend) |
|---|---|
| `_id` | `mongo_id` |
| `accountId` | `owner_zalo_id` |
| `account` | `owner_account` |
| `userId` | `friend_user_id` |
| `fstatus` | `fstatus` (0=陌生 3=好友 5=非好友) |
| `remark` | `remark` |
| `deptId` | `dept_id` |
| `msgSum` | `msg_sum` |
| `latestMsgTime` | `latest_msg_time` |
| `CreatedAt` | `gmt_create` |
| `info.uuid` | `friend_uuid` |
| `info.dpn` | `display_name` |
| `info.usr` | `user_name` |
| `info.avt` | `avatar` |
| `info.cover` | `cover` |
| `info.ged` | `gender` |
| `info.phone` | `phone` |
| `info.stt` | `status_text` |
| `info.ac` | `action_count` |

### 2.3 POST `/api/collect/messages`

采集 WS `code=17` 下发的聊天记录。支持整个 `{code:17,data:{list,total,page,pageSize}}` 结构, 只读取 `data.list`。

**唯一键**: `(accountid, fid, msgid)` 复合唯一 -> 重复推送会更新 `seen/isread/issend` 等状态

**字段映射**:
| 源字段 (WS code=17 data.list[i]) | 表字段 (zalo_message) |
|---|---|
| `_id` | `mongo_id` |
| `msgid` | `msg_id` |
| `accountid` | `owner_zalo_id` |
| `account` | `owner_account` |
| `fid` | `peer_user_id` |
| `groupId` | `group_id` |
| `content` | `content` |
| `type` | `msg_type` (1=文本 2=图片 3=语音 4=视频 5=系统 7=贴图 8=卡片) |
| `from` | `direction` (0=收 其他=发) |
| `issend` | `is_send` (0=已送达 1=发送中 2=失败) |
| `isread` | `is_read` |
| `seen` | `seen` |
| `nickname` | `nickname` |
| `dst` | `dst` (翻译结果) |
| `describe` | `describe_` |
| `bind_id` | `bind_id` |
| `deptId` | `dept_id` |
| `CreatedAt` | `gmt_create` |

---

## 3. 管理后台查询接口 (只读)

所有 `/api/admin/**` 接口需要 `Authorization: Bearer <JWT>`。

### 3.1 GET `/api/admin/accounts`

**Query 参数**:
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` | int | 否 | 页码, 从 1 开始, 默认 1 |
| `size` | int | 否 | 每页条数, 默认 20 |
| `zaloId` | long | 否 | Zalo 账号 ID (精确匹配) |
| `account` | string | 否 | 手机号 (模糊匹配) |
| `nickName` | string | 否 | 昵称 (模糊匹配) |
| `deptId` | long | 否 | 部门 ID |
| `online` | int | 否 | 1=在线 2=离线 |
| `accountStatus` | int | 否 | 账号状态 |

**返回**:
```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "records": [{ /* ZaloAccount 实体 */ }],
    "total": 25,
    "size": 20,
    "current": 1,
    "pages": 2
  }
}
```

### 3.2 GET `/api/admin/accounts/{id}`

根据主键 id 查询账号详情。

### 3.3 GET `/api/admin/friends`

**Query 参数**:
| 参数 | 类型 | 说明 |
|---|---|---|
| `page`, `size` | int | 分页 |
| `ownerZaloId` | long | 归属账号 (精确) |
| `friendUserId` | long | 好友 userId (精确) |
| `displayName` | string | 好友显示名 (模糊) |
| `phone` | string | 好友手机号 (模糊) |
| `fstatus` | int | 0/3/5 |
| `deptId` | long | 部门 |

### 3.4 GET `/api/admin/messages`

**Query 参数**:
| 参数 | 类型 | 说明 |
|---|---|---|
| `page`, `size` | int | 分页 |
| `ownerZaloId` | long | 归属账号 |
| `peerUserId` | long | 对端 userId (fid) |
| `groupId` | long | 群 ID, 0=一对一 |
| `msgType` | int | 1~8 |
| `direction` | int | 0=收 1=发 |
| `contentLike` | string | 消息正文模糊匹配 |
| `startTime` | datetime | `yyyy-MM-dd HH:mm:ss` |
| `endTime` | datetime | `yyyy-MM-dd HH:mm:ss` |

---

## 4. 健康检查

### GET `/api/health`

无需鉴权, 用于运维探测。

```json
{ "code": 0, "msg": "ok", "data": { "service": "zalo-bg", "ts": 1777042302390 } }
```

---

## 5. 调用示例

### 完整流程示例

```bash
BASE=http://43.128.109.91:8801
COLLECT_TOKEN=<拿到的采集 token>

# 1) 推账号列表
curl -X POST "$BASE/api/collect/accounts" \
  -H "X-Collect-Token: $COLLECT_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @ws_code1_response.json

# 2) 推好友列表
curl -X POST "$BASE/api/collect/friends" \
  -H "X-Collect-Token: $COLLECT_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @ws_code7_response.json

# 3) 推聊天记录
curl -X POST "$BASE/api/collect/messages" \
  -H "X-Collect-Token: $COLLECT_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @ws_code17_response.json

# 4) 登录后台
TOKEN=$(curl -sS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"******"}' | jq -r .data.token)

# 5) 查询所有账号
curl -sS "$BASE/api/admin/accounts?page=1&size=20" \
  -H "Authorization: Bearer $TOKEN" | jq

# 6) 查询某账号的好友
curl -sS "$BASE/api/admin/friends?ownerZaloId=1720462" \
  -H "Authorization: Bearer $TOKEN" | jq

# 7) 查询某好友的聊天记录
curl -sS "$BASE/api/admin/messages?ownerZaloId=1720462&peerUserId=456438089" \
  -H "Authorization: Bearer $TOKEN" | jq
```
