import type { ProductPresentation } from "@/api/generated/admin";
import { Button } from "@/components/ui/button";

import "./product-presentation-editor.css";

export function ProductPresentationEditor({
  value,
  onChange,
}: {
  value?: ProductPresentation;
  onChange: (value: ProductPresentation) => void;
}) {
  const images = value?.images ?? [];
  const specifications = value?.specifications ?? [];
  const update = (patch: Partial<ProductPresentation>) =>
    onChange({ images, specifications, imageNote: value?.imageNote, ...patch });
  return (
    <div className="admin-field-wide presentation-editor">
      <fieldset>
        <legend>商品图片</legend>
        <p>
          最多 8 张，首张用于商品目录。填写 HTTPS 地址或 /media/products/
          下的图片路径。
        </p>
        {images.map((image, index) => (
          <div className="presentation-row" key={index}>
            <label className="field">
              <span>图片 {index + 1} 地址</span>
              <input
                required
                maxLength={2048}
                value={image.url}
                onChange={(event) =>
                  update({
                    images: images.map((item, i) =>
                      i === index ? { ...item, url: event.target.value } : item,
                    ),
                  })
                }
              />
            </label>
            <label className="field">
              <span>图片 {index + 1} 说明</span>
              <input
                required
                maxLength={160}
                value={image.alt}
                onChange={(event) =>
                  update({
                    images: images.map((item, i) =>
                      i === index ? { ...item, alt: event.target.value } : item,
                    ),
                  })
                }
              />
            </label>
            <Button
              type="button"
              variant="ghost"
              aria-label={`移除图片 ${index + 1}`}
              onClick={() =>
                update({ images: images.filter((_, i) => i !== index) })
              }
            >
              移除
            </Button>
          </div>
        ))}
        <Button
          type="button"
          variant="secondary"
          disabled={images.length >= 8}
          onClick={() => update({ images: [...images, { url: "", alt: "" }] })}
        >
          添加图片
        </Button>
        <label className="field presentation-note">
          <span>图片来源或用途说明</span>
          <input
            maxLength={160}
            placeholder="例如：AI 生成的演示图，仅用于体验购物流程"
            value={value?.imageNote ?? ""}
            onChange={(event) => update({ imageNote: event.target.value })}
          />
        </label>
      </fieldset>
      <fieldset>
        <legend>商品规格</legend>
        <p>最多 12 项，用于详情页和助手对比表。</p>
        {specifications.map((spec, index) => (
          <div className="presentation-row" key={index}>
            <label className="field">
              <span>规格 {index + 1} 名称</span>
              <input
                required
                maxLength={40}
                value={spec.name}
                onChange={(event) =>
                  update({
                    specifications: specifications.map((item, i) =>
                      i === index
                        ? { ...item, name: event.target.value }
                        : item,
                    ),
                  })
                }
              />
            </label>
            <label className="field">
              <span>规格 {index + 1} 内容</span>
              <input
                required
                maxLength={160}
                value={spec.value}
                onChange={(event) =>
                  update({
                    specifications: specifications.map((item, i) =>
                      i === index
                        ? { ...item, value: event.target.value }
                        : item,
                    ),
                  })
                }
              />
            </label>
            <Button
              type="button"
              variant="ghost"
              aria-label={`移除规格 ${index + 1}`}
              onClick={() =>
                update({
                  specifications: specifications.filter((_, i) => i !== index),
                })
              }
            >
              移除
            </Button>
          </div>
        ))}
        <Button
          type="button"
          variant="secondary"
          disabled={specifications.length >= 12}
          onClick={() =>
            update({
              specifications: [...specifications, { name: "", value: "" }],
            })
          }
        >
          添加规格
        </Button>
      </fieldset>
    </div>
  );
}
