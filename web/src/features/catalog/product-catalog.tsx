import { useInfiniteQuery } from "@tanstack/react-query";
import { Search, SlidersHorizontal } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Link } from "react-router-dom";

import { apiClients } from "@/api/clients";
import { ErrorState, LoadingState } from "@/components/async-states";
import { Button } from "@/components/ui/button";

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

export function ProductCatalog() {
  const [draft, setDraft] = useState(emptyFilters);
  const [filters, setFilters] = useState(emptyFilters);
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

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFilters(draft);
  };

  return (
    <section
      className="catalog-section"
      id="catalog"
      aria-labelledby="catalog-title"
    >
      <div className="section-heading compact-heading">
        <div>
          <p className="eyebrow">CATALOG SHELF</p>
          <h2 id="catalog-title">先看清商品，再开始购买。</h2>
        </div>
      </div>
      <form className="catalog-filter" onSubmit={submit} aria-label="商品筛选">
        <label className="field field-wide">
          <span>搜索</span>
          <span className="input-with-icon">
            <Search aria-hidden="true" />
            <input
              value={draft.keyword}
              onChange={(event) =>
                setDraft({ ...draft, keyword: event.target.value })
              }
              placeholder="商品名称或描述"
            />
          </span>
        </label>
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
            onChange={(event) =>
              setDraft({ ...draft, minPrice: event.target.value })
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
            onChange={(event) =>
              setDraft({ ...draft, maxPrice: event.target.value })
            }
          />
        </label>
        <Button type="submit">
          <SlidersHorizontal aria-hidden="true" />
          应用筛选
        </Button>
      </form>
      {query.isLoading ? <LoadingState label="正在读取商品目录" /> : null}
      {query.isError ? (
        <ErrorState
          description="商品目录没有同步成功，请检查网络后重试。"
          onRetry={() => void query.refetch()}
        />
      ) : null}
      {!query.isLoading && !query.isError && products.length === 0 ? (
        <p className="empty-inline">
          没有符合条件的商品。调整分类或价格范围后再试。
        </p>
      ) : null}
      <div className="product-grid">
        {products.map((product) => (
          <article className="product-card" key={product.productId}>
            <div className="product-visual" aria-hidden="true">
              <span>{product.category.slice(0, 2).toUpperCase()}</span>
              <strong>{product.productId.padStart(4, "0").slice(-4)}</strong>
            </div>
            <div className="product-copy">
              <p>{product.category}</p>
              <h3>{product.name}</h3>
              <span>{product.description ?? "暂无商品描述"}</span>
              <div>
                <strong>¥ {product.price}</strong>
                <small>
                  {product.stock > 0 ? `库存 ${product.stock}` : "已售罄"}
                </small>
              </div>
              <Button asChild variant="secondary">
                <Link to={`/products/${product.productId}`}>
                  查看商品与购买入口
                </Link>
              </Button>
            </div>
          </article>
        ))}
      </div>
      {query.hasNextPage ? (
        <div className="load-more">
          <Button
            type="button"
            variant="secondary"
            disabled={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            {query.isFetchingNextPage ? "正在读取下一页…" : "继续浏览"}
          </Button>
        </div>
      ) : null}
    </section>
  );
}
