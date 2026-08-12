# jt-console-ui

车辆监控平台的控制台前端：实时监控地图、车辆档案、车队管理、电子围栏、告警处置与轨迹回放。

## 来源声明

本目录是 **[SoybeanAdmin](https://github.com/soybeanjs/soybean-admin) v2.2.0 的二次开发版本**，
遵循 MIT License（Copyright © 2021 Soybean），上游许可证原文保留在同级的 [LICENSE](./LICENSE)。

沿用自上游的部分：布局系统、主题引擎、文件路由约定（`@elegant-router`）、请求层、
国际化框架，以及 `packages/@sa/*` 内部工作区包。

本项目新增的部分：`src/views` 下的业务页面（monitor / vehicle / fleet / geofence / alarm / track）、
`src/service/api/console.ts` 接口层、高德地图与 WebSocket 相关的 hooks、以及对接
`jt-console` 后端的认证与数据流。

上游的 CHANGELOG、README 与 issue 模板已移除，避免把使用者引向 SoybeanAdmin 仓库。
如需了解框架本身的用法，请查阅上游文档：<https://docs.soybeanjs.cn>。

## 技术栈

Vue 3 · Vite · TypeScript · Pinia · NaiveUI · UnoCSS · ECharts

## 开发

```bash
pnpm install
pnpm dev          # 开发服务器，读 .env.test
pnpm build        # 生产构建，读 .env.prod
pnpm typecheck
```

### 配置高德地图 key

仓库内的 `.env.prod` / `.env.test` 中 `VITE_AMAP_KEY` 与 `VITE_AMAP_SECURITY_CODE`
**故意留空**，请勿把真实密钥写进这两个文件（它们受版本控制）。

在本目录新建 `.env.local`（已被 `.gitignore` 排除）：

```dotenv
VITE_AMAP_KEY=你申请的key
VITE_AMAP_SECURITY_CODE=你的安全密钥
```

key 需到[高德开放平台](https://lbs.amap.com)申请「Web端(JS API)」类型。留空时地图区域会降级为
提示占位，车辆列表、告警、车队等其余功能不受影响。

> 安全密钥（jscode）按高德的设计属于服务端凭据，放在前端等同于公开配额与计费。
> 生产环境应改为由 nginx 反向代理 `/_AMapService`，在服务端注入。

### 后端地址

`.env.test` 默认指向 `http://127.0.0.1:8300`（本地运行的 `jt-console`）。
`.env.prod` 使用 `/api` 相对路径，由 nginx 反向代理到后端，详见仓库根目录的部署说明。
