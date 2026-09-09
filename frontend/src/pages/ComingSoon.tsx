/** A page that is on the plan but not built yet, so the navigation is honest rather than broken. */
export default function ComingSoon({ title, slice, blurb }: { title: string; slice: number; blurb: string }) {
  return (
    <>
      <div className="page-title">
        <h1>{title}</h1>
      </div>
      <section className="card">
        <p className="muted">{blurb}</p>
        <p className="muted small">
          Arrives with slice {slice} of <code>docs/plans/CONSOLE.md</code>.
        </p>
      </section>
    </>
  );
}
