# /cdn — 本地化的前端依赖

这里存放了原本通过 `https://unpkg.com/...` 引用的前端依赖, 改为
随 jar 打包一起分发, 在 index.html 中通过 `/cdn/xxx` 相对路径加载.

## 原因

在 mainland China 直接访问 unpkg.com 经常超时或被阻断, 导致
admin 后台页面 (Vue + Element Plus 应用) 永远 mount 不起来,
浏览器表现为白屏 / 一直转圈圈. 自托管这几个静态文件可以彻底
消除外部网络依赖.

## 清单 (pinned 版本)

| 文件 | 来源 (unpkg) | 大小 |
|---|---|---|
| `vue.global.prod.js` | `vue@3.4.38/dist/vue.global.prod.js` | 144K |
| `element-plus.css` | `element-plus@2.7.7/dist/index.css` | 320K |
| `element-plus.full.min.js` | `element-plus@2.7.7/dist/index.full.min.js` | 938K |
| `element-plus-icons.iife.min.js` | `@element-plus/icons-vue@2.3.1/dist/index.iife.min.js` | 205K |
| `axios.min.js` | `axios@1.7.2/dist/axios.min.js` | 52K |

## 升级方式

如果将来需要升版本, 替换文件 + 同步更新 index.html 里引用的版本号注释即可:

```bash
cd src/main/resources/static/cdn
curl -sL -o vue.global.prod.js https://unpkg.com/vue@<新版本>/dist/vue.global.prod.js
# ... 其它文件同理
```
