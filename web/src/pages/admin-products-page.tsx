import { type FormEvent, useState } from "react";
import { Pencil, Plus, Search, Trash2, X } from "lucide-react";

import { findApiProblemError } from "@/api/core/problem";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  adminApi,
  type AdminProduct,
  type ProductMutation,
} from "@/features/admin/admin-api";
import {
  DataPanel,
  PageHeading,
  Pager,
  ResourceBoundary,
  useAdminResource,
} from "@/features/admin/admin-ui";

const emptyDraft: ProductMutation = {
  name: "",
  price: "",
  stock: 0,
  category: "",
  description: "",
  reason: "",
};

function ProductEditor({
  product,
  onSaved,
  onClose,
}: {
  product?: AdminProduct;
  onSaved: () => void;
  onClose: () => void;
}) {
  const [draft, setDraft] = useState<ProductMutation>(
    product
      ? {
          name: product.name,
          price: product.price,
          stock: product.stock,
          category: product.category,
          description: product.description ?? "",
          reason: "",
        }
      : emptyDraft,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const update = (key: keyof ProductMutation, value: string | number) =>
    setDraft((current) => ({ ...current, [key]: value }));
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      if (product) await adminApi.updateProduct(product.productId, draft);
      else await adminApi.createProduct(draft);
      onSaved();
    } catch (caught) {
      const problem = findApiProblemError(caught)?.problem;
      setError(
        problem
          ? `${problem.detail}（请求 ID ${problem.requestId}）`
          : caught instanceof Error
            ? caught.message
            : "商品写入失败。",
      );
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="admin-editor" aria-labelledby="product-editor-title">
      <header>
        <div>
          <p className="eyebrow">AUDITED MUTATION</p>
          <h3 id="product-editor-title">
            {product ? `编辑 ${product.name}` : "新增商品"}
          </h3>
        </div>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          aria-label="关闭编辑器"
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </Button>
      </header>
      <form
        className="admin-form-grid"
        onSubmit={(event) => void submit(event)}
      >
        <label className="field">
          <span>商品名称</span>
          <input
            required
            maxLength={200}
            value={draft.name}
            onChange={(event) => update("name", event.target.value)}
          />
        </label>
        <label className="field">
          <span>分类</span>
          <input
            required
            maxLength={100}
            value={draft.category}
            onChange={(event) => update("category", event.target.value)}
          />
        </label>
        <label className="field">
          <span>金额（两位小数）</span>
          <input
            required
            inputMode="decimal"
            pattern="^(0|[1-9][0-9]*)\.[0-9]{2}$"
            placeholder="6999.00"
            value={draft.price}
            onChange={(event) => update("price", event.target.value)}
          />
        </label>
        <label className="field">
          <span>库存</span>
          <input
            required
            type="number"
            min={0}
            step={1}
            value={draft.stock}
            onChange={(event) => update("stock", Number(event.target.value))}
          />
        </label>
        <label className="field admin-field-wide">
          <span>描述</span>
          <textarea
            maxLength={4000}
            rows={3}
            value={draft.description}
            onChange={(event) => update("description", event.target.value)}
          />
        </label>
        <label className="field admin-field-wide">
          <span>变更原因</span>
          <input
            required
            minLength={3}
            maxLength={256}
            value={draft.reason}
            onChange={(event) => update("reason", event.target.value)}
          />
          <small>原因会随管理员、请求与 Trace 一并进入后端审计。</small>
        </label>
        {error ? (
          <p className="inline-problem admin-field-wide" role="alert">
            {error}
          </p>
        ) : null}
        <div className="admin-form-actions admin-field-wide">
          <Button type="submit" disabled={busy}>
            {busy ? "正在提交…" : "提交并记录审计"}
          </Button>
          <Button type="button" variant="ghost" onClick={onClose}>
            取消
          </Button>
        </div>
      </form>
    </section>
  );
}

export function AdminProductsPage() {
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [cursor, setCursor] = useState<string>();
  const [editing, setEditing] = useState<AdminProduct | "new">();
  const [deleteTarget, setDeleteTarget] = useState<AdminProduct>();
  const [deleteReason, setDeleteReason] = useState("");
  const [deleteError, setDeleteError] = useState<string>();
  const [deleting, setDeleting] = useState(false);
  const resource = useAdminResource(
    () => adminApi.products(cursor, keyword),
    [cursor, keyword],
  );
  const finishMutation = () => {
    setEditing(undefined);
    setCursor(undefined);
    resource.reload();
  };
  const remove = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    setDeleteError(undefined);
    try {
      await adminApi.deleteProduct(deleteTarget.productId, deleteReason);
      setDeleteTarget(undefined);
      setDeleteReason("");
      resource.reload();
    } catch (caught) {
      const problem = findApiProblemError(caught)?.problem;
      setDeleteError(
        problem
          ? `${problem.detail}（请求 ID ${problem.requestId}）`
          : "软删除没有完成。",
      );
    } finally {
      setDeleting(false);
    }
  };
  return (
    <div className="admin-page">
      <PageHeading
        eyebrow="CATALOG CONTROL"
        title="商品管理"
        description="查询与写入都使用真实 Admin API；金额、库存和原因由后端再次校验。"
        actions={
          <Button type="button" onClick={() => setEditing("new")}>
            <Plus aria-hidden="true" />
            新增商品
          </Button>
        }
      />
      {editing ? (
        <ProductEditor
          product={editing === "new" ? undefined : editing}
          onClose={() => setEditing(undefined)}
          onSaved={finishMutation}
        />
      ) : null}
      {deleteTarget ? (
        <section
          className="admin-confirm"
          role="dialog"
          aria-modal="true"
          aria-labelledby="delete-product-title"
        >
          <h3 id="delete-product-title">确认软删除商品</h3>
          <p>
            资源 ID：<code>{deleteTarget.productId}</code>
            。商品将从正常查询中移除，操作会被审计。
          </p>
          <label className="field">
            <span>删除原因</span>
            <input
              autoFocus
              required
              minLength={3}
              maxLength={256}
              value={deleteReason}
              onChange={(event) => setDeleteReason(event.target.value)}
            />
          </label>
          {deleteError ? (
            <p className="inline-problem" role="alert">
              {deleteError}
            </p>
          ) : null}
          <div className="admin-form-actions">
            <Button
              type="button"
              disabled={deleting || deleteReason.trim().length < 3}
              onClick={() => void remove()}
            >
              <Trash2 aria-hidden="true" />
              {deleting ? "正在删除…" : "确认软删除"}
            </Button>
            <Button
              type="button"
              variant="ghost"
              onClick={() => setDeleteTarget(undefined)}
            >
              取消
            </Button>
          </div>
        </section>
      ) : null}
      <DataPanel title="商品目录" detail="稳定游标分页">
        <form
          className="admin-filter-bar"
          role="search"
          onSubmit={(event) => {
            event.preventDefault();
            setCursor(undefined);
            setKeyword(keywordInput.trim());
          }}
        >
          <label>
            <span className="sr-only">商品关键字</span>
            <input
              placeholder="名称或分类"
              value={keywordInput}
              onChange={(event) => setKeywordInput(event.target.value)}
            />
          </label>
          <Button type="submit" variant="secondary" size="sm">
            <Search aria-hidden="true" />
            查询
          </Button>
        </form>
        <ResourceBoundary
          resource={resource}
          emptyTitle="没有符合条件的商品"
          emptyDescription="修改关键词，或新增一个真实商品。"
          isEmpty={(page) => page.items.length === 0}
        >
          {(page) => (
            <>
              <div className="admin-table-wrap">
                <table className="admin-table">
                  <thead>
                    <tr>
                      <th>商品</th>
                      <th>分类</th>
                      <th>价格</th>
                      <th>库存</th>
                      <th>资源 ID</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((product) => (
                      <tr key={product.productId}>
                        <td>
                          <strong>{product.name}</strong>
                          <small>{product.description || "无描述"}</small>
                        </td>
                        <td>
                          <Badge>{product.category}</Badge>
                        </td>
                        <td>¥ {product.price}</td>
                        <td>{product.stock}</td>
                        <td className="mono-cell">{product.productId}</td>
                        <td>
                          <div className="row-actions">
                            <Button
                              type="button"
                              variant="ghost"
                              size="sm"
                              onClick={() => setEditing(product)}
                            >
                              <Pencil aria-hidden="true" />
                              编辑
                            </Button>
                            <Button
                              type="button"
                              variant="ghost"
                              size="sm"
                              onClick={() => {
                                setDeleteTarget(product);
                                setDeleteReason("");
                              }}
                            >
                              <Trash2 aria-hidden="true" />
                              删除
                            </Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager
                page={page}
                loading={resource.loading}
                onNext={setCursor}
              />
            </>
          )}
        </ResourceBoundary>
      </DataPanel>
    </div>
  );
}
