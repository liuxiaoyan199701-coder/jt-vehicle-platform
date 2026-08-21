-- 原子「查询并登记」：键不存在或已 DEAD 时写入新流，否则复用。
-- KEYS[1]: stream key
-- KEYS[2]: stream index key
-- ARGV[1]: externalId（index 成员）
-- ARGV[2]: stream entry JSON
-- ARGV[3]: pending TTL 秒数（字符串数字）
local existing = redis.call('GET', KEYS[1])
if existing ~= false then
  local data = cjson.decode(existing)
  if data.state ~= 'DEAD' then
    return 0
  end
end
redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
