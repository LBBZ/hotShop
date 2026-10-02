export interface CatalogResult {
  mode: "search" | "compare" | "detail";
  productIds: string[];
}

export function catalogAnswer(answer: string, catalog: CatalogResult | null) {
  if (!catalog || (answer.trim() && !answer.trim().startsWith("{")))
    return answer;
  if (!catalog.productIds.length)
    return "没有找到相关商品。试试更短的关键词，例如「耳机」或「居家」。";
  if (catalog.mode === "detail")
    return "这是这件商品的最新信息，可以查看详情或准备购买草稿。";
  return catalog.mode === "compare"
    ? "把价格、库存和规格放在一起，看看哪一件更适合你。"
    : "找到了这些商品。可以查看详情、选两件对比，或准备购买草稿。";
}
