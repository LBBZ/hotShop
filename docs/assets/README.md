# 项目截图

README 中的三张截图来自 HotShop 的实际页面组件。截图脚本拦截 API 并提供固定展示数据，不连接真实账号、不创建订单、不调用外部模型。展示数据与一键演示脚本的最小种子数据不同，也不代表真实运营指标。

| 文件                             | 内容                                   |
| -------------------------------- | -------------------------------------- |
| [catalog.png](catalog.png)       | 首页商品区域：搜索、分类与三个商品卡片 |
| [assistant.png](assistant.png)   | AI 助手完成购买草稿，等待用户确认      |
| [operations.png](operations.png) | 管理员运营概览，数据来源标注为演示数据 |
| [storefront.png](storefront.png) | 首页首屏与导航，保留作完整视觉参考     |

## 重新生成

按照 [Web 开发指南](../../web/README.md)安装依赖，在 `web/` 目录启动预览服务：

```powershell
pnpm dev --host 127.0.0.1 --port 4173 --strictPort
```

另开终端，同样在 `web/` 目录运行：

```powershell
pnpm exec playwright install chromium
node scripts/capture-docs.mjs
```

脚本更新前三张 PNG，固定视口为 1440 × 980、时区为 Asia/Shanghai，并启用减少动态效果。商品截图裁剪到商品区域；其他页面按内容完整捕获。`storefront.png` 是单独保留的首页截图，不由本脚本生成。

如需其他本地端口，可通过 `HOTSHOP_DOCS_URL` 指定地址；脚本只接受回环地址。未识别的 API、外部请求或页面 JavaScript 错误会使生成失败。实现见 [capture-docs.mjs](../../web/scripts/capture-docs.mjs)。
