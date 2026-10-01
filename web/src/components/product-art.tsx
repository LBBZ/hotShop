import { Camera, Coffee, Shirt, ShoppingBag, Smartphone } from "lucide-react";

import "./product-art.css";

type ArtKind =
  | "headphones"
  | "keyboard"
  | "speaker"
  | "camera"
  | "coffee"
  | "apparel"
  | "phone"
  | "bag";

function resolveKind(name: string, category: string): ArtKind {
  const subject = `${name} ${category}`;
  if (/耳机|耳麦|headphone|earbud/i.test(subject)) return "headphones";
  if (/键盘|keyboard/i.test(subject)) return "keyboard";
  if (/音箱|音响|收音机|speaker|radio/i.test(subject)) return "speaker";
  if (/相机|摄影|camera/i.test(subject)) return "camera";
  if (/咖啡|杯|茶|coffee|mug/i.test(subject)) return "coffee";
  if (/衣|服饰|衫|shirt|clothing/i.test(subject)) return "apparel";
  if (/手机|phone/i.test(subject)) return "phone";
  return "bag";
}

/** Deliberately stylized category artwork; never presented as a product photo. */
export function ProductArt({
  name,
  category,
}: {
  name: string;
  category: string;
}) {
  const kind = resolveKind(name, category);
  const FallbackIcon =
    {
      camera: Camera,
      coffee: Coffee,
      apparel: Shirt,
      phone: Smartphone,
      bag: ShoppingBag,
    }[kind as "camera" | "coffee" | "apparel" | "phone" | "bag"] ?? ShoppingBag;

  return (
    <div className="product-art" data-art-kind={kind} aria-hidden="true">
      <svg className="product-art-scene" viewBox="0 0 360 260" fill="none">
        <ellipse className="art-floor" cx="180" cy="225" rx="94" ry="12" />
        <g className="art-object">
          {kind === "headphones" ? (
            <g transform="rotate(-13 180 130)">
              <path
                d="M112 149V113a68 68 0 0 1 136 0v36"
                stroke="var(--art-deep)"
                strokeWidth="23"
                strokeLinecap="round"
              />
              <path
                d="M112 117a68 68 0 0 1 136 0"
                stroke="var(--art-light)"
                strokeWidth="14"
                strokeLinecap="round"
              />
              <rect
                x="93"
                y="125"
                width="48"
                height="78"
                rx="23"
                fill="var(--art-deep)"
              />
              <rect
                x="95"
                y="125"
                width="34"
                height="74"
                rx="17"
                fill="var(--art-mid)"
              />
              <path
                d="M104 143v36"
                stroke="var(--art-light)"
                strokeWidth="5"
                strokeLinecap="round"
              />
              <rect
                x="219"
                y="125"
                width="48"
                height="78"
                rx="23"
                fill="var(--art-deep)"
              />
              <rect
                x="229"
                y="125"
                width="35"
                height="74"
                rx="17"
                fill="var(--art-mid)"
              />
              <path
                d="M244 144v33"
                stroke="var(--art-light)"
                strokeWidth="5"
                strokeLinecap="round"
              />
            </g>
          ) : kind === "keyboard" ? (
            <g transform="rotate(-12 180 130)">
              <rect
                x="60"
                y="85"
                width="240"
                height="115"
                rx="18"
                fill="var(--art-deep)"
              />
              <rect
                x="60"
                y="76"
                width="240"
                height="115"
                rx="18"
                fill="var(--art-mid)"
              />
              {[0, 1, 2].map((row) =>
                Array.from({ length: 9 }, (_, col) => (
                  <rect
                    key={`${row}-${col}`}
                    x={73 + col * 25}
                    y={89 + row * 24}
                    width="19"
                    height="17"
                    rx="4"
                    fill={col === 8 ? "var(--art-deep)" : "var(--art-light)"}
                  />
                )),
              )}
              <rect
                x="74"
                y="162"
                width="28"
                height="15"
                rx="4"
                fill="var(--art-light)"
              />
              <rect
                x="108"
                y="162"
                width="117"
                height="15"
                rx="4"
                fill="var(--art-light)"
              />
              <rect
                x="231"
                y="162"
                width="55"
                height="15"
                rx="4"
                fill="var(--art-deep)"
              />
            </g>
          ) : kind === "speaker" ? (
            <g transform="rotate(-10 180 130)">
              <path
                d="M139 78V65a15 15 0 0 1 15-15h52a15 15 0 0 1 15 15v13"
                stroke="var(--art-deep)"
                strokeWidth="9"
              />
              <rect
                x="94"
                y="74"
                width="181"
                height="131"
                rx="24"
                fill="var(--art-deep)"
              />
              <rect
                x="87"
                y="69"
                width="181"
                height="131"
                rx="24"
                fill="var(--art-mid)"
              />
              <circle cx="146" cy="136" r="40" fill="var(--art-deep)" />
              <circle
                cx="146"
                cy="136"
                r="29"
                stroke="var(--art-mid)"
                strokeWidth="2"
              />
              <circle
                cx="146"
                cy="136"
                r="20"
                stroke="var(--art-mid)"
                strokeWidth="2"
              />
              <circle cx="146" cy="136" r="11" fill="var(--art-mid)" />
              <rect
                x="205"
                y="106"
                width="41"
                height="16"
                rx="5"
                fill="var(--art-light)"
              />
              <circle cx="216" cy="154" r="11" fill="var(--art-light)" />
              <circle cx="241" cy="154" r="6" fill="var(--art-deep)" />
            </g>
          ) : (
            <g transform="rotate(-12 180 130)">
              <rect
                x="102"
                y="56"
                width="160"
                height="160"
                rx="39"
                fill="var(--art-deep)"
              />
              <rect
                x="94"
                y="48"
                width="160"
                height="160"
                rx="39"
                fill="var(--art-mid)"
              />
              <FallbackIcon
                x="124"
                y="78"
                width="100"
                height="100"
                stroke="var(--art-light)"
                strokeWidth="1.3"
              />
            </g>
          )}
        </g>
      </svg>
      <span className="product-art-note">品类示意</span>
    </div>
  );
}
