import { useId } from "react";

import "./shopping-sculpture.css";

/** Original vector artwork: the everyday shopping bag, made a little extraordinary. */
export function ShoppingSculpture() {
  const id = useId().replaceAll(":", "");
  const paint = (name: string) => `url(#${id}-${name})`;

  return (
    <div className="shopping-sculpture">
      <svg
        className="shopping-sculpture__canvas"
        viewBox="0 0 580 570"
        role="img"
        aria-labelledby={`${id}-title`}
        aria-describedby={`${id}-description`}
      >
        <title id={`${id}-title`}>HotShop 原创购物袋插画</title>
        <desc id={`${id}-description`}>
          带有 H 标记的粉色购物袋，搭配奶黄色小购物袋和绿色圆球。
        </desc>
        <defs>
          <linearGradient id={`${id}-front`} x1="0" y1="0" x2="1" y2="0.8">
            <stop offset="0" stopColor="#f5c6de" />
            <stop offset="0.44" stopColor="#edb3cd" />
            <stop offset="0.82" stopColor="#dfa0bf" />
            <stop offset="1" stopColor="#c779a1" />
          </linearGradient>
          <linearGradient id={`${id}-side`} x1="0" y1="0" x2="1" y2="0.3">
            <stop offset="0" stopColor="#b95b8a" />
            <stop offset="0.36" stopColor="#d689ad" />
            <stop offset="0.58" stopColor="#bc648e" />
            <stop offset="1" stopColor="#a9507d" />
          </linearGradient>
          <linearGradient id={`${id}-inside`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#a75b7e" />
            <stop offset="1" stopColor="#6c3e5d" />
          </linearGradient>
          <linearGradient id={`${id}-handle`} x1="0" y1="0" x2="1" y2="0.6">
            <stop offset="0" stopColor="#f6cce1" />
            <stop offset="0.36" stopColor="#e8aaca" />
            <stop offset="0.72" stopColor="#c678a1" />
            <stop offset="1" stopColor="#eeb8d3" />
          </linearGradient>
          <linearGradient id={`${id}-butter`} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#fff3bd" />
            <stop offset="0.63" stopColor="#f6e8a3" />
            <stop offset="1" stopColor="#d6c275" />
          </linearGradient>
          <radialGradient id={`${id}-sphere`} cx="30%" cy="25%" r="75%">
            <stop offset="0" stopColor="#91b49c" />
            <stop offset="0.42" stopColor="#628c71" />
            <stop offset="0.78" stopColor="#46705e" />
            <stop offset="1" stopColor="#2e5043" />
          </radialGradient>
          <radialGradient id={`${id}-ground`}>
            <stop offset="0" stopColor="#49354f" stopOpacity="0.22" />
            <stop offset="0.55" stopColor="#49354f" stopOpacity="0.09" />
            <stop offset="1" stopColor="#49354f" stopOpacity="0" />
          </radialGradient>
          <filter
            id={`${id}-soften`}
            x="-50%"
            y="-100%"
            width="200%"
            height="300%"
          >
            <feGaussianBlur stdDeviation="7" />
          </filter>
          <filter
            id={`${id}-contact`}
            x="-50%"
            y="-50%"
            width="200%"
            height="200%"
          >
            <feGaussianBlur stdDeviation="3" />
          </filter>
        </defs>

        <ellipse cx="310" cy="503" rx="222" ry="38" fill={paint("ground")} />
        <ellipse
          cx="291"
          cy="493"
          rx="124"
          ry="11"
          fill="#59425e"
          opacity="0.09"
          filter={paint("soften")}
        />

        <g className="shopping-sculpture__bags">
          <g transform="rotate(12 430 340)">
            <path
              d="M390 237 394 188C395 154 449 153 451 187L454 241"
              fill="none"
              stroke="#c7b368"
              strokeWidth="12"
              strokeLinecap="round"
            />
            <path d="m362 228 130-3 18 210-144 9Z" fill={paint("butter")} />
            <path d="m492 225 29 19 7 177-18 14Z" fill="#c4ae66" />
            <path d="m362 228 27-16 132 32-29-19Z" fill="#bca568" />
            <path
              d="m396 250-3-55c-2-33 47-36 49-3l3 55"
              fill="none"
              stroke="#fff0b5"
              strokeWidth="11"
              strokeLinecap="round"
            />
            <path
              d="m397 249-3-54c-1-32 44-34 47-3l3 55"
              fill="none"
              stroke="#fff8d4"
              strokeWidth="1.3"
              opacity="0.65"
            />
            <path
              d="m474 365 10 9-7 11 13-2 4 13 5-13 13 2-8-11 10-9-13 1-7-12-6 12Z"
              fill="#786847"
              opacity="0.65"
            />
            <path
              d="m373 433 128-8"
              fill="none"
              stroke="#fff8d3"
              strokeWidth="1"
              opacity="0.6"
            />
          </g>

          <g className="shopping-sculpture__main-bag">
            <path
              d="m232 168-5-52c-6-63 84-87 94-20l9 60"
              fill="none"
              stroke="#a9537e"
              strokeWidth="17"
              strokeLinecap="round"
            />
            <path
              d="m229 166-5-52c-6-63 84-87 94-20l9 60"
              fill="none"
              stroke={paint("handle")}
              strokeWidth="13"
              strokeLinecap="round"
            />

            <path d="m113 210 69-43 241-25-68 43Z" fill={paint("inside")} />
            <path
              d="m182 167 241-25-7 24-232 24Z"
              fill="#d493b1"
              opacity="0.62"
            />
            <path d="m355 185 68-43 32 267-63 49Z" fill={paint("side")} />
            <path
              d="m423 144-22 52 54 213-44-30Z"
              fill="#eaa4c7"
              opacity="0.19"
            />
            <path
              d="m401 197 10 183-19 76"
              fill="none"
              stroke="#963f6a"
              strokeWidth="1.2"
              opacity="0.32"
            />
            <path d="m113 210 242-25 37 273-243 32Z" fill={paint("front")} />
            <path
              d="m115 212 35 276 240-31"
              fill="none"
              stroke="#ffdaec"
              strokeWidth="1.4"
              opacity="0.72"
            />
            <path
              d="m355 187 35 268"
              fill="none"
              stroke="#f7d0e2"
              strokeWidth="1"
              opacity="0.58"
            />
            <path
              d="m151 477 21-21 201-23 17 22Z"
              fill="#a75581"
              opacity="0.05"
            />

            <path
              d="m185 231-8-55c-11-75 92-88 102-15l8 58"
              fill="none"
              stroke="#af6089"
              strokeWidth="16"
              strokeLinecap="round"
              opacity="0.24"
              filter={paint("contact")}
              transform="translate(4 5)"
            />
            <path
              d="m182 225-8-55c-11-75 92-88 102-15l8 58"
              fill="none"
              stroke="#b66791"
              strokeWidth="17"
              strokeLinecap="round"
            />
            <path
              d="m180 224-8-55c-11-75 92-88 102-15l8 58"
              fill="none"
              stroke={paint("handle")}
              strokeWidth="14"
              strokeLinecap="round"
            />
            <path
              d="m177 223-8-54c-10-72 91-85 101-15l8 57"
              fill="none"
              stroke="#fbd5e9"
              strokeWidth="1.5"
              strokeLinecap="round"
              opacity="0.64"
            />

            <g transform="translate(254 339) rotate(-7)">
              <path
                d="M-49-52h23v40h44v-40h23V57H18V12h-44v45h-23Z"
                fill="#b83267"
              />
              <circle cx="57" cy="46" r="11" fill="#b83267" />
              <text
                x="0"
                y="89"
                textAnchor="middle"
                fontFamily="Manrope, sans-serif"
                fontWeight="750"
                fontSize="15"
                letterSpacing="3.5"
                fill="#94345d"
              >
                HOTSHOP
              </text>
            </g>
          </g>
        </g>

        <g className="shopping-sculpture__sphere">
          <circle cx="100" cy="399" r="32" fill={paint("sphere")} />
          <ellipse
            cx="92"
            cy="386"
            rx="13"
            ry="8"
            fill="#bdcdbb"
            opacity="0.12"
            transform="rotate(-35 92 386)"
            filter={paint("contact")}
          />
        </g>
        <g transform="translate(466 97) rotate(12)" fill="#b83267">
          <path d="M-27-6h19l-5-19 12-4 6 20 15-13 9 10L12 1l19 5-4 13-20-6 5 20-13 3-4-21-15 14-9-10 17-14-19-4Z" />
        </g>
        <path
          d="M57 207h16m-8-8v16"
          stroke="#a08ca6"
          strokeWidth="1.5"
          strokeLinecap="round"
          opacity="0.8"
        />
      </svg>
    </div>
  );
}
