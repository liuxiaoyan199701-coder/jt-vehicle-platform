# 开流 JWT 对接契约

本文档定义业务系统与 `jt-api` 之间的开流凭证约定。JWT 仅用于确认调用方已登录，
`jt-api` 不查询用户与设备的数据权限，也不会在验签过程中调用业务系统。

## 签名与公钥

- 签名算法固定为 `RS256`，不接受 `none`、`HS256` 或其他算法。
- JWT 头必须包含 `alg=RS256`、`typ=JWT` 和非空 `kid`。
- 业务系统持有 RSA 私钥并签发 JWT；`jt-api` 仅持有公钥。
- 业务系统通过 HTTPS JWKS 端点发布公钥。每个 JWK 必须包含 `kty=RSA`、`kid`、
  `n`、`e`，建议显式包含 `alg=RS256` 与 `use=sig`。
- `jt-api` 按 `kid` 选择公钥并缓存 JWKS，默认缓存 10 分钟；未知 `kid` 会触发一次刷新。

JWKS 示例：

```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "business-key-2026-08",
      "n": "<base64url-modulus>",
      "e": "AQAB"
    }
  ]
}
```

## 载荷字段

以下字段均为必填：

| 字段 | 类型 | 含义 |
|---|---|---|
| `sub` | string | 登录用户的稳定标识 |
| `iat` | number | 签发时间，Unix 秒 |
| `exp` | number | 过期时间，Unix 秒 |
| `jti` | string | JWT 唯一标识，为后续吊销能力预留 |

建议 JWT 有效期不超过 2 小时。`jt-api` 对 `iat` 与 `exp` 使用 60 秒时钟偏差容忍；
部署方应确保业务系统和网关均使用可靠的时间同步服务。

JWT 载荷示例：

```json
{
  "sub": "user-123",
  "iat": 1786352400,
  "exp": 1786359600,
  "jti": "login-session-456"
}
```

## 请求与失败语义

开流请求使用 `Authorization: Bearer <JWT>`。缺少凭证、格式错误、算法不符、未知 `kid`、
签名无效、已过期或必需字段缺失时，`jt-api` 返回 HTTP `401`，且不会登记流或下发信令。

验签成功后，`jt-api` 为本次媒体连接签发独立的一次性短效 token。该 token 与业务 JWT
不是同一种凭证：客户端只将它发送给开流响应指定的媒体节点，媒体节点不接收业务 JWT，
也不访问 JWKS 或业务系统。

## 配置

```yaml
jt:
  auth:
    stream:
      mode: jwt
      jwks-uri: https://auth.example.com/.well-known/jwks.json
      jwks-cache-ttl: 10m
      token-ttl: 60s
```

默认 `mode` 为 `disabled`，用于零外部依赖启动；生产环境开启 `jwt` 后，`jwks-uri` 必填。
