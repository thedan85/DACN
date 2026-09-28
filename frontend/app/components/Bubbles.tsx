import type { CSSProperties } from "react";

export function Bubbles({ count = 14 }: { count?: number }) {
  const spans = Array.from({ length: count }, (_, i) => {
    const left = (i * 67 + 13) % 100;
    const size = 4 + ((i * 37) % 11);
    const duration = 6 + ((i * 53) % 7);
    const delay = (i * 29) % 9;
    const style = {
      "--bx": `${left}%`,
      "--bs": `${size}px`,
      "--bd": `${duration}s`,
      "--bdel": `${delay}s`,
    } as CSSProperties;
    return <span key={i} style={style} />;
  });
  return (
    <div className="bubble-layer" aria-hidden="true">
      {spans}
    </div>
  );
}
