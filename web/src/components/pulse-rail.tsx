const pulseNodes = [
  { label: "发现", value: "Catalog" },
  { label: "预留", value: "Reserved" },
  { label: "成单", value: "Ordered" },
  { label: "支付", value: "Paid" },
];

export function PulseRail() {
  return (
    <div className="pulse-rail" aria-label="从商品发现到支付的交易路径">
      <div className="pulse-rail-line" aria-hidden="true">
        <span />
      </div>
      <ol>
        {pulseNodes.map((node) => (
          <li key={node.value}>
            <span className="pulse-node" aria-hidden="true" />
            <span className="font-utility text-[0.65rem] tracking-[0.16em] text-[var(--ink-subtle)] uppercase">
              {node.label}
            </span>
            <strong>{node.value}</strong>
          </li>
        ))}
      </ol>
    </div>
  );
}
