import { Expand, Minus, Plus, X } from "lucide-react";
import { useRef, useState } from "react";

import type { ProductImage, ProductPresentation } from "@/api/generated/public";
import { ProductArt } from "@/components/product-art";
import { Button } from "@/components/ui/button";

import "./product-gallery.css";

export function ProductPhoto({
  image,
  name,
  category,
  eager = false,
}: {
  image?: ProductImage;
  name: string;
  category: string;
  eager?: boolean;
}) {
  const [failedUrl, setFailedUrl] = useState<string>();
  return (
    <div className="product-photo">
      {image && failedUrl !== image.url ? (
        <img
          src={image.url}
          alt={image.alt}
          width={1024}
          height={1024}
          loading={eager ? "eager" : "lazy"}
          decoding="async"
          referrerPolicy="no-referrer"
          onError={() => setFailedUrl(image.url)}
        />
      ) : (
        <ProductArt name={name} category={category} />
      )}
    </div>
  );
}

export function ProductGallery({
  name,
  category,
  presentation,
}: {
  name: string;
  category: string;
  presentation?: ProductPresentation;
}) {
  const images = presentation?.images ?? [];
  const [selected, setSelected] = useState(0);
  const [zoomed, setZoomed] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  const image = images[selected];
  return (
    <section className="product-gallery" aria-label={`${name}图集`}>
      <div className="gallery-main">
        <ProductPhoto image={image} name={name} category={category} eager />
        {image ? (
          <Button
            type="button"
            variant="secondary"
            className="gallery-expand"
            onClick={() => {
              setZoomed(false);
              dialog.current?.showModal();
            }}
          >
            <Expand aria-hidden="true" /> 放大查看
          </Button>
        ) : null}
      </div>
      {images.length > 1 ? (
        <div className="gallery-thumbnails" aria-label="选择商品图片">
          {images.map((item, index) => (
            <button
              key={`${item.url}-${index}`}
              type="button"
              aria-label={`查看图片 ${index + 1}：${item.alt}`}
              aria-pressed={index === selected}
              onClick={() => setSelected(index)}
            >
              <ProductPhoto image={item} name={name} category={category} />
            </button>
          ))}
        </div>
      ) : null}
      <p className="gallery-caption">
        {images.length
          ? presentation?.imageNote || "商品图片 · 具体信息请查看商品说明"
          : "暂未提供商品图片 · 图案仅为品类示意"}
      </p>
      <dialog
        ref={dialog}
        className="gallery-dialog"
        aria-label={`${name}大图`}
        onClick={(event) => {
          if (event.target === event.currentTarget) dialog.current?.close();
        }}
      >
        <header>
          <h2>{name}</h2>
          <Button
            type="button"
            variant="secondary"
            aria-label={zoomed ? "缩小图片" : "放大图片"}
            onClick={() => setZoomed(!zoomed)}
          >
            {zoomed ? (
              <Minus aria-hidden="true" />
            ) : (
              <Plus aria-hidden="true" />
            )}
            {zoomed ? "100%" : "200%"}
          </Button>
          <Button
            type="button"
            variant="ghost"
            autoFocus
            aria-label="关闭大图"
            onClick={() => dialog.current?.close()}
          >
            <X aria-hidden="true" />
          </Button>
        </header>
        <div
          className={zoomed ? "gallery-zoom is-zoomed" : "gallery-zoom"}
          tabIndex={0}
          aria-label="商品大图，可滚动查看放大细节"
        >
          <ProductPhoto image={image} name={name} category={category} eager />
        </div>
        <p>
          {image?.alt}
          {zoomed ? " · 滚动查看细节" : ""}
        </p>
      </dialog>
    </section>
  );
}
