local reservation = KEYS[1]
local stock = KEYS[2]
local user_slot = KEYS[3]

if redis.call('TYPE', reservation).ok ~= 'hash' then
    return {'INVALID_TYPE'}
end
local current_status = redis.call('HGET', reservation, 'status')
local current_order = redis.call('HGET', reservation, 'orderId')
if current_status == 'PAYMENT_EXPIRED' then
    if current_order == ARGV[2] then return {'IDEMPOTENT'} end
    return {'FACT_CONFLICT'}
end
if current_status ~= 'ORDER_CREATED' or current_order ~= ARGV[2]
   or redis.call('HGET', reservation, 'reservationNo') ~= ARGV[1]
   or redis.call('HGET', reservation, 'activityId') ~= ARGV[3]
   or redis.call('HGET', reservation, 'userId') ~= ARGV[4]
   or redis.call('HGET', reservation, 'productId') ~= ARGV[5]
   or redis.call('HGET', reservation, 'quantity') ~= ARGV[6] then
    return {'FACT_CONFLICT'}
end
local current_stock = tonumber(redis.call('GET', stock))
if current_stock == nil then return {'STOCK_INVALID'} end
local next_stock = current_stock + tonumber(ARGV[6])
redis.call('SET', stock, tostring(next_stock), 'KEEPTTL')
redis.call('HSET', reservation, 'status', 'PAYMENT_EXPIRED',
    'paymentExpiredAtMs', ARGV[7], 'reasonCode', 'PAYMENT_TIMEOUT')
local slot = redis.call('GET', user_slot)
if slot == ARGV[1] then redis.call('DEL', user_slot) end
return {'COMPENSATED', tostring(next_stock)}
