import { Loader2 } from "lucide-react";

/** Full-page placeholder while a route chunk, the session or the profile loads. */
const PageSpinner = ({ label = "Đang tải..." }: { label?: string }) => (
  <div role="status" className="flex min-h-screen flex-col items-center justify-center gap-3 bg-background">
    <Loader2 className="h-6 w-6 animate-spin text-primary" aria-hidden="true" />
    <span className="text-sm text-muted-foreground">{label}</span>
  </div>
);

export default PageSpinner;
