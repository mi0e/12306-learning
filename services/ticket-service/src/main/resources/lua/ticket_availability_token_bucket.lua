-- KEYS[2] 就是咱们用户购买的出发站点和到达站点，比如北京南_南京南
local inputString = KEYS[2]
local actualKey = inputString
-- 因为 Redis Key 序列化器的问题，会把 mading: 给加上
-- 所以这里需要把 mading: 给删除，仅保留北京南_南京南
-- actualKey 就是北京南_南京南
local colonIndex = string.find(actualKey, ":")
if colonIndex ~= nil then
    actualKey = string.sub(actualKey, colonIndex + 1)
end

-- ARGV[1] 是需要扣减的座位类型以及对应数量
local jsonArrayStr = ARGV[1]
local jsonArray = cjson.decode(jsonArrayStr)

-- 判断对于不同座位类型是否有足量令牌
for index, jsonObj in ipairs(jsonArray) do
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    local actualInnerHashKey = actualKey .. "_" .. seatType
    local ticketSeatAvailabilityTokenValue = tonumber(redis.call('hget', KEYS[1], tostring(actualInnerHashKey)))
    if ticketSeatAvailabilityTokenValue < count then
        return 1
    end
end

-- 途径站点
local alongJsonArrayStr = ARGV[2]
local alongJsonArray = cjson.decode(alongJsonArrayStr)

-- 扣减令牌
for index, jsonObj in ipairs(jsonArray) do
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    for indexTwo, alongJsonObj in ipairs(alongJsonArray) do
        local startStation = tostring(alongJsonObj.startStation)
        local endStation = tostring(alongJsonObj.endStation)
        local actualInnerHashKey = startStation .. "_" .. endStation .. "_" .. seatType
        redis.call('hincrby', KEYS[1], tostring(actualInnerHashKey), -count)
    end
end

return 0
