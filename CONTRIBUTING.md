# 参与贡献

欢迎 issue 和 PR。这个项目面向真实的部标车辆监控场景，最有价值的贡献往往来自
**你在对接真实车机时踩到的坑**。

## 提 Issue

对接问题请尽量附上这些信息，能省去大量来回：

- 终端厂商与协议版本（JT/T 808-2011 / 2013 / 2019）
- 网关日志片段：`journalctl -u jt-platform -f` 或 `docker compose logs gateway`
- 如果是「设备连上了但界面看不到」，先看诊断接口：`GET /api/diagnostics/events`，
  它会告诉你收到了哪些报文以及每条的处理结果
- 如果是视频问题，`jt-console/tools/inspect-stream.mjs` 可以抓裸流并解析 SPS/PPS，
  直接看出终端推的是什么

## 开发环境

```bash
# 网关与业务后端（JDK 25 + Maven 3.9）
cd jt-platform && mvn -B clean package
cd jt-console  && mvn -B clean package

# 前端（Node ≥ 20.19，pnpm ≥ 10.5）
cd jt-console-ui && pnpm install && pnpm dev
```

前端需要在 `jt-console-ui/.env.local` 里填自己的高德地图 key，
仓库内的 `.env.prod` / `.env.test` 只放占位符，**不要把真实密钥提交上来**。

## 代码约定

- 注释写「为什么」，不写「是什么」。协议实现里那些反直觉的地方（分包、转义、
  BCD 编码、坐标系）尤其需要说明来由
- 提交信息用中文或英文都可以，说清改动动机
- Java 侧新增依赖前请确认许可证与 Apache-2.0 兼容，并在 `NOTICE` 中登记
- 涉及协议行为的改动，请附上对应的报文样例或测试

## 许可证

提交贡献即表示你同意以 [Apache License 2.0](LICENSE) 授权你的代码。

本项目衍生自 yezhihao/jt808-server（Apache-2.0）、jt1078-stream-server（MIT）与
SoybeanAdmin（MIT），修改这些衍生代码时请保留原有的版权声明与文件头。
