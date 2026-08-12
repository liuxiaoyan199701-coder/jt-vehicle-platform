# 控制台前端：Vite 构建产物由 nginx 提供，并反向代理后端接口与媒体裸流。
#
# 构建上下文是仓库根目录（见 docker-compose.yml 的 context: .），因为前端通过
# file:../packages/jt-player 引用播放器 SDK，必须保持与仓库一致的目录布局。
# 播放器的 dist 不在版本控制内，所以要在容器里先把它构建出来。

FROM node:22-alpine AS build
WORKDIR /build
RUN corepack enable

# --- 1. 构建播放器 SDK（零运行时依赖，用它自带的 npm lock）---
COPY packages/jt-player ./packages/jt-player
WORKDIR /build/packages/jt-player
RUN npm ci --no-audit --no-fund && npm run build

# --- 2. 安装前端依赖（先只复制清单，源码改动时可命中依赖缓存）---
WORKDIR /build/jt-console-ui
COPY jt-console-ui/package.json jt-console-ui/pnpm-lock.yaml jt-console-ui/pnpm-workspace.yaml ./
COPY jt-console-ui/packages ./packages
RUN pnpm install --frozen-lockfile

# --- 3. 构建前端 ---
COPY jt-console-ui/ ./
RUN pnpm build

FROM nginx:alpine
COPY --from=build /build/jt-console-ui/dist /usr/share/nginx/html
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
