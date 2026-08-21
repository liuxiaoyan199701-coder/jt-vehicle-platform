-- 校验并消费一次性 token（原子）。
-- KEYS[1]: token key
-- KEYS[2]: token index key
-- ARGV[1]: expected streamKey（externalId）
-- ARGV[2]: expected mediaInstanceId
-- ARGV[3]: now epoch millis（字符串数字）
-- ARGV[4]: token（用于 index 清理）
local existing = redis.call('GET', KEYS[1])
if existing == false then
  return 'MISSING'
end
local data = cjson.decode(existing)
if tonumber(data.expiresAtMillis) <= tonumber(ARGV[3]) then
  redis.call('DEL', KEYS[1])
  redis.call('SREM', KEYS[2], ARGV[4])
  return 'EXPIRED'
end
if data.streamKey ~= ARGV[1] then
  return 'WRONG_STREAM'
end
if data.mediaInstanceId ~= ARGV[2] then
  return 'WRONG_INSTANCE'
end
if data.consumed == true then
  return 'REPLAYED'
end
data.consumed = true
redis.call('SET', KEYS[1], cjson.encode(data), 'KEEPTTL')
return 'VALID'
