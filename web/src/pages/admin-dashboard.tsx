import { ShieldCheck, SlidersHorizontal } from "lucide-react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { useStore } from "zustand";

import { PermissionGate } from "@/components/permission-gate";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { adminAuth } from "@/auth/domains";

const flowData = [
  { tick: "10:00", reservations: 18, orders: 12 },
  { tick: "10:04", reservations: 42, orders: 31 },
  { tick: "10:08", reservations: 34, orders: 29 },
  { tick: "10:12", reservations: 67, orders: 51 },
  { tick: "10:16", reservations: 53, orders: 48 },
  { tick: "10:20", reservations: 79, orders: 64 },
];

export function AdminDashboard() {
  const session = useStore(adminAuth.store, (state) => state.session);
  const isAdministrator = session?.role === "ROLE_ADMIN";

  return (
    <div className="dashboard-stack">
      <header className="dashboard-heading">
        <div>
          <p className="eyebrow">OPERATIONS BOARD</p>
          <h2>交易脉冲总览</h2>
          <p>这是一组演示数据，用于验证图表、布局与状态表达。</p>
        </div>
        <Badge tone="warning">DEMO SIGNAL</Badge>
      </header>

      <section className="operations-grid">
        <article className="chart-panel">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">FLOW SHAPE</p>
              <h3>预留 / 成单趋势</h3>
            </div>
            <span className="font-utility">20 MIN WINDOW</span>
          </div>
          <div className="chart-frame">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart
                data={flowData}
                margin={{ top: 12, right: 8, left: -22, bottom: 0 }}
                accessibilityLayer
              >
                <defs>
                  <linearGradient
                    id="reservationFill"
                    x1="0"
                    y1="0"
                    x2="0"
                    y2="1"
                  >
                    <stop offset="0%" stopColor="#ef4c5b" stopOpacity={0.34} />
                    <stop
                      offset="100%"
                      stopColor="#ef4c5b"
                      stopOpacity={0.02}
                    />
                  </linearGradient>
                </defs>
                <CartesianGrid
                  stroke="#dbe3eb"
                  strokeDasharray="2 6"
                  vertical={false}
                />
                <XAxis
                  dataKey="tick"
                  tickLine={false}
                  axisLine={false}
                  tick={{ fill: "#6b7a8c", fontSize: 11 }}
                />
                <YAxis
                  tickLine={false}
                  axisLine={false}
                  tick={{ fill: "#6b7a8c", fontSize: 11 }}
                />
                <Tooltip
                  contentStyle={{
                    borderRadius: 10,
                    border: "1px solid #cbd6e2",
                    boxShadow: "0 14px 40px rgba(16,37,63,.12)",
                  }}
                />
                <Area
                  type="monotone"
                  dataKey="reservations"
                  name="预留"
                  stroke="#ef4c5b"
                  strokeWidth={2.5}
                  fill="url(#reservationFill)"
                />
                <Area
                  type="monotone"
                  dataKey="orders"
                  name="成单"
                  stroke="#087f8c"
                  strokeWidth={2}
                  fill="transparent"
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </article>
        <article className="control-panel">
          <ShieldCheck aria-hidden="true" />
          <p className="eyebrow">AUTHORITY CHECK</p>
          <h3>界面权限只是第一道提示。</h3>
          <p>
            下面的操作只对当前 Administrator 体验可见；真正的产品写入仍必须由
            Admin API 逐次授权。
          </p>
          <PermissionGate allowed={isAdministrator}>
            <Button type="button" variant="dark" disabled>
              <SlidersHorizontal aria-hidden="true" className="size-4" />
              配置活动（后续接入）
            </Button>
          </PermissionGate>
        </article>
      </section>

      <section className="signal-strip" aria-label="Administrator 身份域状态">
        <div>
          <span>ISSUER DOMAIN</span>
          <strong>Administrator only</strong>
        </div>
        <div>
          <span>ACCESS STORAGE</span>
          <strong>Memory / isolated</strong>
        </div>
        <div>
          <span>FINAL AUTHORITY</span>
          <strong>Admin API</strong>
        </div>
      </section>
    </div>
  );
}
