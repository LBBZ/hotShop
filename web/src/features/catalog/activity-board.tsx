import { useQuery } from "@tanstack/react-query";
import { Radio, TimerReset, WifiOff } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { ApiProblemError, findApiProblemError } from "@/api/core/problem";
import { apiClients } from "@/api/clients";
import { userAuth } from "@/auth/domains";
import { ErrorState, LoadingState } from "@/components/async-states";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  getActivities,
  type FlashSaleActivity,
} from "@/features/catalog/activity-api";
import { getActivityAvailability } from "@/features/catalog/activity-availability";
import {
  getOrCreatePurchaseIntent,
  purchaseIntentFingerprint,
} from "@/features/transactions/purchase-intent";

function useCalibratedCountdown(activity: FlashSaleActivity) {
  const offset = useMemo(
    () => new Date(activity.serverTime).getTime() - Date.now(),
    [activity.serverTime],
  );
  const [now, setNow] = useState(() => Date.now() + offset);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now() + offset), 1000);
    return () => window.clearInterval(timer);
  }, [offset]);
  const target = new Date(
    activity.phase === "UPCOMING" ? activity.startsAt : activity.endsAt,
  ).getTime();
  const remaining = Math.max(0, target - now);
  const hours = Math.floor(remaining / 3_600_000);
  const minutes = Math.floor((remaining % 3_600_000) / 60_000);
  const seconds = Math.floor((remaining % 60_000) / 1000);
  return {
    label: `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`,
    expired: activity.phase === "LIVE" && remaining === 0,
  };
}

function intentStorageKey(activityId: string) {
  return `hotshop.purchase-intent.flash-sale.${activityId}`;
}

function ActivityCard({ activity }: { activity: FlashSaleActivity }) {
  const navigate = useNavigate();
  const countdown = useCalibratedCountdown(activity);
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState<ApiProblemError | Error | null>(null);
  const availability = getActivityAvailability(
    activity.phase,
    countdown.expired,
  );
  const availabilityId = `activity-availability-${activity.activityId}`;

  const reserve = async () => {
    setSubmitting(true);
    setProblem(null);
    try {
      if (!userAuth.store.getState().session) {
        const restored = await userAuth.ensureSession();
        if (!restored) {
          void navigate(`/auth?returnTo=${encodeURIComponent("/")}`);
          return;
        }
      }
      const intent = getOrCreatePurchaseIntent(
        intentStorageKey(activity.activityId),
        purchaseIntentFingerprint({
          activityId: activity.activityId,
          quantity: 1,
        }),
        "flash",
      );
      const idempotencyKey = intent.key;
      let attempt = 0;
      while (true) {
        try {
          const reservation = await apiClients.user.flashSales.reserve({
            activityId: activity.activityId,
            idempotencyKey,
            flashSaleReservationRequest: { quantity: 1 },
          });
          sessionStorage.setItem(
            `hotshop.reservation.${reservation.reservationNo}`,
            JSON.stringify({
              activityId: activity.activityId,
              requestId: reservation.requestId,
              idempotencyKey,
            }),
          );
          void navigate(
            `/user/reservations/${activity.activityId}/${reservation.reservationNo}`,
          );
          return;
        } catch (error) {
          const apiProblem = findApiProblemError(error);
          const recoverable =
            apiProblem !== undefined &&
            (apiProblem.problem.status === 429 ||
              apiProblem.problem.status >= 500);
          if (!recoverable || attempt >= 2) throw apiProblem ?? error;
          attempt += 1;
          await new Promise((resolve) =>
            window.setTimeout(resolve, 400 * attempt),
          );
        }
      }
    } catch (error) {
      setProblem(error instanceof Error ? error : new Error("预约未完成"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <article
      className="drop-card"
      data-activity-id={activity.activityId}
      data-phase={activity.phase}
    >
      <div className="drop-card-head">
        <Badge tone={availability.disabled ? "neutral" : "signal"}>
          {availability.statusLabel}
        </Badge>
        <span>{activity.activityCode}</span>
      </div>
      <p className="drop-category">{activity.category ?? "精选商品"}</p>
      <h3>{activity.productName}</h3>
      <p className="drop-description">
        {activity.description ?? "活动商品详情以服务端事实为准。"}
      </p>
      <div className="drop-price">
        <span>¥</span>
        <strong>{activity.salePrice}</strong>
        <small>限购 {activity.perUserLimit} 件</small>
      </div>
      <div className="drop-window">
        <TimerReset aria-hidden="true" />
        <span>{activity.phase === "UPCOMING" ? "距开始" : "距结束"}</span>
        <strong>{countdown.expired ? "已结束" : countdown.label}</strong>
      </div>
      <p className="stock-line" id={availabilityId}>
        剩余 {activity.availableStock} 件 · 时间已按服务端校准 ·{" "}
        {availability.reason}
      </p>
      {problem ? (
        <p className="inline-problem" role="alert">
          {problem instanceof ApiProblemError && problem.problem.status === 429
            ? `请求过快，请稍后重试。请求 ID ${problem.problem.requestId}`
            : problem.message}
        </p>
      ) : null}
      <div className="drop-actions">
        <Button
          type="button"
          onClick={() => void reserve()}
          disabled={availability.disabled || submitting}
          aria-describedby={availability.disabled ? availabilityId : undefined}
        >
          {submitting ? "正在确认唯一预约…" : availability.buttonLabel}
        </Button>
        <Button asChild variant="secondary">
          <Link to={`/products/${activity.productId}`}>商品详情</Link>
        </Button>
      </div>
    </article>
  );
}

export function ActivityBoard() {
  const query = useQuery({
    queryKey: ["flash-sale-activities"],
    queryFn: getActivities,
    refetchInterval: 15_000,
  });
  if (query.isLoading) return <LoadingState label="正在校准活动窗口" />;
  if (query.isError) {
    return (
      <ErrorState
        title={navigator.onLine ? "活动窗口暂时不可用" : "当前处于离线状态"}
        description="商品浏览仍可继续；恢复网络后可重新同步活动。"
        onRetry={() => void query.refetch()}
      />
    );
  }
  return (
    <section className="drop-board" aria-labelledby="drop-title">
      <div className="section-heading compact-heading">
        <div>
          <p className="eyebrow">LIVE DROP BOARD</p>
          <h2 id="drop-title">活动窗口，以服务端时间为准。</h2>
        </div>
        <p className="board-status">
          {navigator.onLine ? (
            <Radio aria-hidden="true" />
          ) : (
            <WifiOff aria-hidden="true" />
          )}
          {navigator.onLine ? "15 秒同步一次" : "等待网络恢复"}
        </p>
      </div>
      {query.data?.length ? (
        <div className="drop-grid">
          {query.data.map((activity) => (
            <ActivityCard key={activity.activityId} activity={activity} />
          ))}
        </div>
      ) : (
        <p className="empty-inline">
          目前没有开放或即将开始的活动，请先浏览普通商品。
        </p>
      )}
    </section>
  );
}
