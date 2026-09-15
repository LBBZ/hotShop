import { useState, type FormEvent } from "react";

import { findApiProblemError } from "@/api/core/problem";
import { Button } from "@/components/ui/button";
import { adminApi, type AdminProduct } from "@/features/admin/admin-api";

export function StockAdjustmentEditor({
  product,
  onSaved,
  onClose,
}: {
  product: AdminProduct;
  onSaved: () => void;
  onClose: () => void;
}) {
  const [snapshot, setSnapshot] = useState(product);
  const [delta, setDelta] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState<string>();
  const quantity = Number(delta);
  const valid =
    delta.trim() !== "" &&
    Number.isInteger(quantity) &&
    quantity !== 0 &&
    quantity >= -2147483648 &&
    quantity <= 2147483647 &&
    snapshot.stock + quantity >= 0 &&
    snapshot.stock + quantity <= 2147483647;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!valid || conflict || busy) return;
    setBusy(true);
    setError(undefined);
    try {
      await adminApi.adjustStock(product.productId, {
        delta: quantity,
        expectedVersion: snapshot.version,
        reason,
      });
      onSaved();
    } catch (caught) {
      const problem = findApiProblemError(caught)?.problem;
      const changed = problem?.code === "STOCK_ADJUSTMENT_CONFLICT";
      setConflict(changed);
      setError(
        changed
          ? `库存已发生变化，本次调整未生效。请刷新当前库存，核对调整后数量再提交。（请求 ID ${problem.requestId}）`
          : problem
            ? `${problem.detail}（请求 ID ${problem.requestId}）`
            : "库存调整失败，请稍后重试。",
      );
    } finally {
      setBusy(false);
    }
  };
  const refresh = async () => {
    setBusy(true);
    try {
      setSnapshot(await adminApi.product(product.productId));
      setConflict(false);
      setError(undefined);
    } catch {
      setError("库存刷新失败，请重试。当前调整尚未提交。");
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="admin-editor" aria-labelledby="stock-adjustment-title">
      <h3 id="stock-adjustment-title">调整 {product.name} 的库存</h3>
      <p>
        当前读取库存：{snapshot.stock}
        。正数表示补货，负数表示盘亏；操作原因和调整数量会记录到审计。
      </p>
      <form
        className="admin-form-grid"
        onSubmit={(event) => void submit(event)}
      >
        <label className="field">
          <span>调整数量</span>
          <input
            required
            type="number"
            step={1}
            value={delta}
            onChange={(event) => setDelta(event.target.value)}
          />
        </label>
        <label className="field">
          <span>调整原因</span>
          <input
            required
            minLength={3}
            maxLength={256}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
        <p className="admin-field-wide" role="status">
          {valid
            ? `调整后库存：${snapshot.stock + quantity}`
            : "请输入非零整数，调整后库存必须在有效范围内。"}
        </p>
        {error ? (
          <p className="inline-problem admin-field-wide" role="alert">
            {error}
          </p>
        ) : null}
        <div className="admin-form-actions admin-field-wide">
          <Button
            type="submit"
            disabled={
              busy ||
              conflict ||
              !valid ||
              reason.trim().length < 3 ||
              !snapshot.version
            }
          >
            确认调整库存
          </Button>
          {conflict ? (
            <Button
              type="button"
              disabled={busy}
              onClick={() => void refresh()}
            >
              刷新当前库存
            </Button>
          ) : null}
          <Button type="button" variant="ghost" onClick={onClose}>
            取消
          </Button>
        </div>
      </form>
    </section>
  );
}
