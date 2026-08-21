-- 任意态 → DEAD（幂等），并移除 PENDING 超时 TTL。
-- KEYS[1]: stream key
-- ARGV[1]: reason
-- ARGV[2]: now（ISO instant 字符串）
local existing = redis.call('GET', KEYS[1])
if existing == false then
  return 0
end
local data = cjson.decode(existing)
if data.state == 'DEAD' then
  return 0
end
data.state = 'DEAD'
data.terminalReason = ARGV[1]
data.lastActiveAt = ARGV[2]
redis.call('SET', KEYS[1], cjson.encode(data))
return 1
