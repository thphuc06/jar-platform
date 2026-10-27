import { Construction } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";

/** Screens that still run on the old data layer are moved to the new API one by one (roadmap P1.11b, P1.11c). */
const ComingSoon = ({ title, step }: { title: string; step: string }) => (
  <div className="space-y-6 animate-slide-up">
    <h1 className="text-2xl font-display font-bold">{title}</h1>
    <Card>
      <CardContent className="flex flex-col items-center gap-3 p-8 text-center">
        <Construction className="h-8 w-8 text-muted-foreground" />
        <p className="text-sm text-muted-foreground">This screen moves to the new API in step {step}.</p>
      </CardContent>
    </Card>
  </div>
);

export default ComingSoon;
