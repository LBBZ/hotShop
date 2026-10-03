import { ArrowLeft, Ban, SearchX, TimerReset } from "lucide-react";
import { Link, useLocation, useSearchParams } from "react-router-dom";

import { Button } from "@/components/ui/button";

interface StatusPageProps {
  code: string;
  eyebrow: string;
  title: string;
  description: string;
  icon: typeof Ban;
  actionTo?: string;
  actionLabel?: string;
}

function StatusPage({
  code,
  eyebrow,
  title,
  description,
  icon: Icon,
  actionTo = "/",
  actionLabel = "返回首页",
}: StatusPageProps) {
  return (
    <main className="status-page">
      <div className="status-code" aria-hidden="true">
        {code}
      </div>
      <div className="status-card">
        <Icon aria-hidden="true" />
        <p className="eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p>{description}</p>
        <Button asChild variant="dark">
          <Link to={actionTo}>
            <ArrowLeft aria-hidden="true" className="size-4" />
            {actionLabel}
          </Link>
        </Button>
      </div>
    </main>
  );
}

export function ForbiddenPage() {
  return (
    <StatusPage
      code="403"
      eyebrow="ACCESS BOUNDARY"
      title="这个操作不属于当前身份域"
      description="切换前端路由或按钮状态不会获得额外权限。请使用具备相应授权的身份重新进入。"
      icon={Ban}
    />
  );
}

export function SessionExpiredPage() {
  const [params] = useSearchParams();
  const location = useLocation();
  const domain = params.get("domain") === "admin" ? "Administrator" : "User";
  const from = (location.state as { from?: unknown } | null)?.from;
  const returnTo =
    typeof from === "string" && /^\/user(?:\/|$)/u.test(from) ? from : "/user";
  return (
    <StatusPage
      code="401"
      eyebrow={`${domain.toUpperCase()} SESSION`}
      title="需要重新登录"
      description={
        domain === "User"
          ? "登录状态未能恢复。请重新登录后继续，已创建的订单仍可在「我的订单」查看。"
          : "登录状态未能恢复，请重新登录后继续处理。"
      }
      actionTo={
        domain === "Administrator"
          ? "/admin/login"
          : `/auth?returnTo=${encodeURIComponent(returnTo)}`
      }
      actionLabel="重新登录"
      icon={TimerReset}
    />
  );
}

export function NotFoundPage() {
  return (
    <StatusPage
      code="404"
      eyebrow="ROUTE NOT FOUND"
      title="这条交易路径不存在"
      description="检查地址，或返回首页重新选择入口。"
      icon={SearchX}
    />
  );
}
