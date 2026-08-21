-- 订阅计数 +1，保留 key TTL（PENDING 流带超时）。
-- KEYS[1]: stream key
-- ARGV[1]: now（ISO instant 字符串）
local existing = redis.call('GET', KEYS[1])
if existing == false then
  return -1
end
local data = cjson.decode(existing)
if data.state == 'DEAD' then
  return -2
end
data.subscriberCount = data.subscriberCount + 1
data.lastActiveAt = ARGV[1]
redis.call('SET', KEYS[1], cjson.encode(data), 'KEEPTTL')
return data.subscriberCount
