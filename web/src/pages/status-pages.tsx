import { ArrowLeft, Ban, SearchX, TimerReset } from "lucide-react";
import { Link, useSearchParams } from "react-router-dom";

import { Button } from "@/components/ui/button";

interface StatusPageProps {
  code: string;
  eyebrow: string;
  title: string;
  description: string;
  icon: typeof Ban;
}

function StatusPage({
  code,
  eyebrow,
  title,
  description,
  icon: Icon,
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
          <Link to="/">
            <ArrowLeft aria-hidden="true" className="size-4" />
            返回首页
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
  const domain = params.get("domain") === "admin" ? "Administrator" : "User";
  return (
    <StatusPage
      code="401"
      eyebrow={`${domain.toUpperCase()} SESSION`}
      title={`${domain} 会话已结束`}
      description="Refresh Session 无法恢复，内存中的 Access Token 已清理。重新登录后可以继续。"
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
