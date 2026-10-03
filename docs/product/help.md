# HotShop 使用说明 / Help

[项目首页](../../README.md) · [文档中心](../README.md)

本页说明仓库当前提供的功能，也是演示助手知识资料的来源。HotShop 使用模拟支付，不连接真实支付机构。
This guide describes the current demo. Payments are simulated; no real money is charged.

## After-sales

当前没有退货、换货、退款或售后工单入口。订单详情页用于查看交易状态与模拟支付场景。
项目未定义真实商家的退换期限、凭证要求或退款到账时间，助手不能替商家承诺这些条件。

The demo has no return, exchange, refund or after-sales request form. The Order details page
shows transaction state and simulated payments. It does not establish a real merchant's return
window, evidence requirements or refund schedule.

## Account

注册和登录使用用户名、密码。出现“需要重新登录”时，使用页面上的登录入口，成功后回到原用户页面。
请勿分享密码、Token 或 Cookie。当前没有短信登录、密码找回或退出所有设备的界面。

Register and sign in with a username and password. If session recovery fails, use the sign-in
action to return to your original user page. Keep credentials private. SMS login, password
reset and a sign-out-all-devices screen are not implemented.

## Support

当前没有站内帮助中心或客服工单。项目问题可通过 [GitHub Issues](https://github.com/LBBZ/hotShop/issues)
反馈，附上版本、复现步骤和脱敏后的错误。公开问题中不要包含个人资料或认证凭据。

There is no built-in help center or support ticket system. Report project issues through
GitHub Issues with the version, reproduction steps and sanitized errors. Keep personal data
and credentials out of public reports.

## Campaigns

预约被接受只代表请求进入处理流程，不等于订单已经创建。在“我的预约”查看最终结果，再进入对应订单。
参与条件以活动信息为准；库存、价格和处理进度以实时查询为准。

An accepted reservation is not yet an order. Check My reservations for its final result and
then open the linked order. Read the activity's participation conditions; availability,
prices and processing progress require live queries.

## Administration

管理员助手提供统计、异常摘要和有限的配置草稿。后台页面上的商品、库存和失败事件操作仍受身份、权限及确认限制。
助手知识文档不会授予退款、补偿、消息重放、权限修改或用户封禁能力。

The administrator assistant supports statistics, anomaly summaries and limited configuration
drafts. Back-office actions still require the corresponding authorization and confirmation.
Knowledge documents do not grant high-risk operational capabilities.
