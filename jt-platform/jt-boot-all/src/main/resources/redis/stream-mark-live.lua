-- PENDING → LIVE（仅一次），并移除 PENDING 超时 TTL。
-- KEYS[1]: stream key
-- ARGV[1]: now（ISO instant 字符串）
local existing = redis.call('GET', KEYS[1])
if existing == false then
  return 0
end
local data = cjson.decode(existing)
if data.state ~= 'PENDING' then
  return 0
end
data.state = 'LIVE'
data.lastActiveAt = ARGV[1]
redis.call('SET', KEYS[1], cjson.encode(data))
return 1
