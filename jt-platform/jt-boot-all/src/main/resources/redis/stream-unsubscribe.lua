-- 订阅计数 -1（下限 0），保留 key TTL。
-- KEYS[1]: stream key
-- ARGV[1]: now（ISO instant 字符串）
local existing = redis.call('GET', KEYS[1])
if existing == false then
  return -1
end
local data = cjson.decode(existing)
data.subscriberCount = math.max(0, data.subscriberCount - 1)
data.lastActiveAt = ARGV[1]
redis.call('SET', KEYS[1], cjson.encode(data), 'KEEPTTL')
return data.subscriberCount
