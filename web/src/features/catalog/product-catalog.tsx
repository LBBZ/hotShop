import { useInfiniteQuery } from "@tanstack/react-query";
import {
  ArrowDown,
  ArrowUpRight,
  Search,
  ShoppingBag,
  SlidersHorizontal,
  X,
} from "lucide-react";
import { type FormEvent, useState } from "react";
import { Link } from "react-router-dom";

import { apiClients } from "@/api/clients";
import { ErrorState, LoadingState } from "@/components/async-states";
import { ProductArt } from "@/components/product-art";
import { Button } from "@/components/ui/button";

import "./catalog.css";

interface Filters {
  keyword: string;
  category: string;
  minPrice: string;
  maxPrice: string;
}

const emptyFilters: Filters = {
  keyword: "",
  category: "",
  minPrice: "",
  maxPrice: "",
};
const priceFormatter = new Intl.NumberFormat("zh-CN", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function ProductCatalog() {
  const [draft, setDraft] = useState(emptyFilters);
  const [filters, setFilters] = useState(emptyFilters);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [filterError, setFilterError] = useState("");
  const query = useInfiniteQuery({
    queryKey: ["products", filters],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      apiClients.public.products.getProducts({
        limit: 9,
        cursor: pageParam,
        keyword: filters.keyword || undefined,
        category: filters.category || undefined,
        minPrice: filters.minPrice ? Number(filters.minPrice) : undefined,
        maxPrice: filters.maxPrice ? Number(filters.maxPrice) : undefined,
      }),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const products = query.data?.pages.flatMap((page) => page.items) ?? [];
  const hasFilters = Object.values(filters).some(Boolean);
  const advancedFilterCount = [
    filters.category,
    filters.minPrice,
    filters.maxPrice,
  ].filter(Boolean).length;

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (
      draft.minPrice &&
      draft.maxPrice &&
      Number(draft.minPrice) > Number(draft.maxPrice)
    ) {
      setFilterError("最低价不能高于最高价，请调整价格范围。");
      setAdvancedOpen(true);
      return;
    }
    setFilterError("");
    setFilters({
      ...draft,
      keyword: draft.keyword.trim(),
      category: draft.category.trim(),
    });
  };

  const resetFilters = () => {
    setDraft(emptyFilters);
    setFilters(emptyFilters);
    setFilterError("");
  };

  return (
    <section
      className="catalog-section"
      id="catalog"
      aria-labelledby="catalog-title"
    >
      <div className="catalog-heading">
        <div>
          <p className="catalog-eyebrow">THE EDIT / GOOD FINDS</p>
          <h2 id="catalog-title">好物，值得慢慢挑。</h2>
        </div>
        <p className="catalog-intro">
          留一点时间，
          <br />
          发现日常里的心动。
        </p>
      </div>

      <form className="catalog-filter" onSubmit={submit} aria-label="商品筛选">
        <div className="catalog-search-row">
          <label className="catalog-search">
            <span className="catalog-sr-only">搜索</span>
            <Search aria-hidden="true" />
            <input
              type="search"
              value={draft.keyword}
              onChange={(event) =>
                setDraft({ ...draft, keyword: event.target.value })
              }
              placeholder="找一件心仪的好物"
            />
          </label>
          <button
            className="catalog-filter-toggle"
            type="button"
            aria-expanded={advancedOpen}
            aria-controls="catalog-advanced-filters"
            onClick={() => setAdvancedOpen(!advancedOpen)}
          >
            <SlidersHorizontal aria-hidden="true" />
            筛选条件
            {advancedFilterCount > 0 ? (
              <span className="catalog-filter-count">
                {advancedFilterCount}
              </span>
            ) : null}
          </button>
          <Button className="catalog-apply" type="submit">
            应用筛选
            <ArrowUpRight aria-hidden="true" />
          </Button>
        </div>
        <div
          id="catalog-advanced-filters"
          className="catalog-advanced"
          hidden={!advancedOpen}
        >
          <label className="field">
            <span>分类</span>
            <input
              value={draft.category}
              onChange={(event) =>
                setDraft({ ...draft, category: event.target.value })
              }
              placeholder="例如 数码"
            />
          </label>
          <label className="field">
            <span>最低价</span>
            <input
              type="number"
              min="0"
              step="0.01"
              inputMode="decimal"
              value={draft.minPrice}
              onChange={(event) => {
                setDraft({ ...draft, minPrice: event.target.value });
                setFilterError("");
              }}
              placeholder="¥ 0.00"
              aria-invalid={Boolean(filterError)}
              aria-describedby={
                filterError ? "catalog-filter-error" : undefined
              }
            />
          </label>
          <label className="field">
            <span>最高价</span>
            <input
              type="number"
              min="0"
              step="0.01"
              inputMode="decimal"
              value={draft.maxPrice}
              onChange={(event) => {
                setDraft({ ...draft, maxPrice: event.target.value });
                setFilterError("");
              }}
              placeholder="不限"
              aria-invalid={Boolean(filterError)}
              aria-describedby={
                filterError ? "catalog-filter-error" : undefined
              }
            />
          </label>
        </div>
        {filterError ? (
          <p
            id="catalog-filter-error"
            className="catalog-filter-error"
            role="alert"
          >
            {filterError}
          </p>
        ) : null}
      </form>

      <div className="catalog-results-bar">
        <p aria-live="polite">
          {query.isLoading ? (
            "正在寻找好物…"
          ) : query.isError ? (
            "商品目录暂未加载"
          ) : (
            <>
              已呈现 <strong>{products.length}</strong> 件商品
              {query.hasNextPage ? " · 下方可继续浏览" : ""}
            </>
          )}
        </p>
        {hasFilters ? (
          <button
            type="button"
            className="catalog-clear"
            onClick={resetFilters}
          >
            <X aria-hidden="true" />
            清除筛选
          </button>
        ) : (
          <span>慢慢逛 · 认真选</span>
        )}
      </div>

      {query.isLoading ? <LoadingState label="正在读取商品目录" /> : null}
      {query.isError ? (
        <ErrorState
          description="商品目录没有同步成功，请检查网络后重试。"
          onRetry={() => void query.refetch()}
        />
      ) : null}
      {!query.isLoading && !query.isError && products.length === 0 ? (
        <div className="catalog-empty">
          <ShoppingBag aria-hidden="true" />
          <h3>
            {hasFilters ? "换个条件，再发现一些好物。" : "这里暂时还没有商品。"}
          </h3>
          <p>
            {hasFilters
              ? "没有符合条件的商品，试试调整关键词、分类或价格范围。"
              : "稍后可以刷新目录，查看最新上架的商品。"}
          </p>
          <Button
            type="button"
            variant="secondary"
            onClick={hasFilters ? resetFilters : () => void query.refetch()}
          >
            {hasFilters ? "清除筛选，浏览全部" : "刷新商品"}
          </Button>
        </div>
      ) : null}

      <div className="product-grid">
        {products.map((product) => (
          <article className="product-card" key={product.productId}>
            <ProductArt name={product.name} category={product.category} />
            <div className="product-copy">
              <p className="product-category">{product.category}</p>
              <h3>{product.name}</h3>
              <p className="product-description">
                {product.description ?? "暂无商品描述"}
              </p>
              <div className="product-meta">
                <strong className="product-price">
                  <span>¥</span> {priceFormatter.format(Number(product.price))}
                </strong>
                <small
                  className={
                    product.stock > 0
                      ? "product-stock"
                      : "product-stock is-sold-out"
                  }
                >
                  {product.stock > 0 ? `库存 ${product.stock}` : "已售罄"}
                </small>
              </div>
              <Link
                className="product-details-link"
                to={`/products/${product.productId}`}
                aria-label={`${product.name}，查看商品与购买入口`}
              >
                <span>查看商品与购买入口</span>
                <ArrowUpRight aria-hidden="true" />
              </Link>
            </div>
          </article>
        ))}
      </div>
      {query.hasNextPage ? (
        <div className="load-more">
          <Button
            className="catalog-load-more"
            type="button"
            variant="secondary"
            disabled={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            {query.isFetchingNextPage ? "正在读取下一页…" : "继续浏览"}
            <ArrowDown aria-hidden="true" />
          </Button>
        </div>
      ) : null}
    </section>
  );
}
