import { useQueries } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";

import { apiClients } from "@/api/clients";
import { ProductPhoto } from "@/components/product-gallery";
import { Button } from "@/components/ui/button";

import type { CatalogResult } from "./catalog-answer";

import "./catalog-results.css";

export function CatalogResults({
  result,
  busy,
  onRequest,
}: {
  result: CatalogResult;
  busy: boolean;
  onRequest: (question: string) => void;
}) {
  const [selected, setSelected] = useState<string[]>([]);
  const queries = useQueries({
    queries: result.productIds.map((productId) => ({
      queryKey: ["product", productId],
      queryFn: () => apiClients.public.products.getProduct({ productId }),
      staleTime: 0,
    })),
  });
  const products = queries.flatMap((query) => (query.data ? [query.data] : []));
  const specs = [
    ...new Set(
      products.flatMap(
        (product) =>
          product.presentation?.specifications?.map((spec) => spec.name) ?? [],
      ),
    ),
  ];
  if (!result.productIds.length) return null;
  return (
    <div
      className={
        result.mode === "compare"
          ? "agent-catalog is-comparison"
          : "agent-catalog"
      }
      aria-label={result.mode === "compare" ? "商品对比" : "推荐商品"}
    >
      {queries.some((query) => query.isPending) ? (
        <p role="status">正在读取商品的最新信息…</p>
      ) : null}
      {queries.some((query) => query.isError) ? (
        <div role="alert">
          <p>部分商品暂时无法读取，请重试或返回商品目录。</p>
          <Button
            variant="secondary"
            onClick={() => {
              queries
                .filter((query) => query.isError)
                .forEach((query) => void query.refetch());
            }}
          >
            重新读取商品
          </Button>
        </div>
      ) : null}
      <div className="agent-catalog-grid">
        {products.map((product) => (
          <article className="agent-product" key={product.productId}>
            <ProductPhoto
              image={product.presentation?.images?.[0]}
              name={product.name}
              category={product.category}
            />
            <div className="agent-product-copy">
              <span className="agent-product-category">{product.category}</span>
              <h4>
                <Link to={`/products/${product.productId}`}>
                  {product.name}
                </Link>
              </h4>
              <p>{product.description}</p>
              <div className="agent-product-price">
                <strong>¥ {product.price}</strong>
                <span>
                  {product.stock > 0 ? `库存 ${product.stock}` : "已售罄"}
                </span>
              </div>
              {result.mode !== "compare" ? (
                <label className="agent-compare-choice">
                  <input
                    type="checkbox"
                    aria-label={`加入对比：${product.name}`}
                    checked={selected.includes(product.productId)}
                    disabled={
                      busy ||
                      (selected.length === 2 &&
                        !selected.includes(product.productId))
                    }
                    onChange={(event) =>
                      setSelected(
                        event.target.checked
                          ? [...selected, product.productId]
                          : selected.filter((id) => id !== product.productId),
                      )
                    }
                  />
                  加入对比<span className="sr-only">：{product.name}</span>
                </label>
              ) : null}
              <Button
                type="button"
                variant="secondary"
                disabled={busy || product.stock === 0}
                onClick={() =>
                  onRequest(`购买商品 ${product.productId} 数量 1`)
                }
              >
                准备购买草稿
              </Button>
            </div>
          </article>
        ))}
      </div>
      {result.mode === "compare" && products.length > 1 ? (
        <div
          className="agent-comparison-scroll"
          role="region"
          aria-label="价格与规格对比表"
          tabIndex={0}
        >
          <table className="agent-comparison">
            <caption>价格与规格 · 以确认下单时的信息为准</caption>
            <thead>
              <tr>
                <th scope="col">比较项</th>
                {products.map((p) => (
                  <th key={p.productId} scope="col">
                    {p.name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">售价</th>
                {products.map((p) => (
                  <td key={p.productId}>¥ {p.price}</td>
                ))}
              </tr>
              <tr>
                <th scope="row">库存</th>
                {products.map((p) => (
                  <td key={p.productId}>{p.stock > 0 ? p.stock : "已售罄"}</td>
                ))}
              </tr>
              {specs.map((name) => (
                <tr key={name}>
                  <th scope="row">{name}</th>
                  {products.map((p) => (
                    <td key={p.productId}>
                      {p.presentation?.specifications?.find(
                        (spec) => spec.name === name,
                      )?.value ?? "未提供"}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : result.mode !== "compare" && products.length > 1 ? (
        <div className="agent-compare-action">
          <p>已选 {selected.length} / 2 件商品</p>
          <Button
            type="button"
            disabled={busy || selected.length !== 2}
            onClick={() =>
              onRequest(`对比商品 ${selected[0]} 和 ${selected[1]}`)
            }
          >
            对比这两件
          </Button>
        </div>
      ) : null}
    </div>
  );
}
