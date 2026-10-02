# 项目截图

README 中的三张截图以及商品详情参考图来自 HotShop 的实际页面组件。截图脚本拦截 API 并提供固定展示数据，不连接真实账号、不创建订单、不调用外部模型。商品画面沿用演示目录素材，展示 ID 与一键演示种子不同；运营数字不代表真实指标。

| 文件                                     | 内容                                                      |
| ---------------------------------------- | --------------------------------------------------------- |
| [catalog.png](catalog.png)               | 首页商品区域：搜索、分类与三个商品卡片                    |
| [assistant.png](assistant.png)           | AI 助手完成购买草稿，等待用户确认                         |
| [product.png](product.png) | 商品图集、规格、数量与购买入口 |
| [operations.png](operations.png)         | 管理员运营概览，数据来源标注为演示数据                    |
| [storefront.png](storefront.png)         | 首页首屏与导航，保留作完整视觉参考                        |
| [social-preview.png](social-preview.png) | GitHub 社交预览，1280 × 640；复用项目购物袋矢量插画和字体 |

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

脚本更新 catalog、product、assistant、operations 四张 PNG，固定视口为 1440 × 980、时区为 Asia/Shanghai，并启用减少动态效果。目录截图裁剪到商品区域；其他页面按内容完整捕获。脚本等待图片解码完成后截图，避免懒加载图片留白。`storefront.png` 是单独保留的首页截图，不由本脚本生成。

## 商品演示图片

`web/public/media/products/` 的 10 张 WebP 是通过内置 imagegen 生成的原创演示图，覆盖 8 件虚构商品。收音机与头戴耳机另有细节图。图片保持原始构图，通过浏览器 Canvas 转为质量 0.86 的 WebP，总计约 1.1 MB；商品规格为虚构演示内容。

[生成记录](product-images.json)保留每张图片的提示词、内置生成模式及源文件路径。原始 PNG 留在本地生成目录；仓库只收录用于网页的 WebP。界面显示 AI 演示图说明，缺图时显示品类示意，不将其标为实物照片。

如需其他本地端口，可通过 `HOTSHOP_DOCS_URL` 指定地址；脚本只接受回环地址。未识别的 API、外部请求或页面 JavaScript 错误会使生成失败。实现见 [capture-docs.mjs](../../web/scripts/capture-docs.mjs)。

## GitHub 社交预览

[social-preview.html](social-preview.html) 是可编辑的卡片源文件。它沿用商城的浅紫、深紫和玫红配色；[生成脚本](../../web/scripts/capture-social-preview.mjs)从本地首页读取现有购物袋 SVG，并嵌入项目字体后输出 PNG。无需外部图片服务。

先启动当前代码的演示应用，再在 `web/` 目录运行：

```powershell
$env:HOTSHOP_PREVIEW_URL = 'http://127.0.0.1:18080'
node scripts/capture-social-preview.mjs
```

生成脚本固定尺寸为 1280 × 640，并检查文件小于 1 MB。中文字体使用系统提供的 Microsoft YaHei 或无衬线字体，因此不同系统可能存在字形差异。

图片提交到仓库后，还需在 [GitHub 仓库设置](https://github.com/LBBZ/hotShop/settings)的 **Social preview → Edit → Upload an image** 中选择 PNG，设置才会生效。尺寸和上传方法以 [GitHub 官方说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/customizing-your-repositorys-social-media-preview)为准。
