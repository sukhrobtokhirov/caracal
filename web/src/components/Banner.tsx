export type Tone = "error" | "warning" | "info" | "success";

/** A labelled message block. `role="alert"` so failures reach screen readers
 * without the user hunting for what changed. */
export function Banner({
  tone,
  title,
  children,
}: {
  tone: Tone;
  title: string;
  children?: React.ReactNode;
}) {
  return (
    <div className={`banner ${tone}`} role={tone === "error" ? "alert" : "status"}>
      <strong>{title}</strong>
      {children ? <span>{children}</span> : null}
    </div>
  );
}
