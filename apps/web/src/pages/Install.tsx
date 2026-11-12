import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, Check, Download, Smartphone } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

const Install = () => {
  const navigate = useNavigate();
  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [isInstalled, setIsInstalled] = useState(() => window.matchMedia("(display-mode: standalone)").matches);

  useEffect(() => {
    const handler = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e as BeforeInstallPromptEvent);
    };
    window.addEventListener("beforeinstallprompt", handler);
    return () => window.removeEventListener("beforeinstallprompt", handler);
  }, []);

  const handleInstall = async () => {
    if (!deferredPrompt) return;
    await deferredPrompt.prompt();
    const { outcome } = await deferredPrompt.userChoice;
    if (outcome === "accepted") setIsInstalled(true);
    setDeferredPrompt(null);
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center">
          <img src="/pwa-192x192.png" alt="" className="mx-auto mb-4 h-20 w-20 rounded-2xl" />
          <CardTitle className="text-2xl font-display">Cài đặt FinAdvisor</CardTitle>
          <p className="mt-2 text-sm text-muted-foreground">Thêm ứng dụng vào màn hình chính để dùng nhanh và mượt hơn</p>
        </CardHeader>
        <CardContent className="space-y-4">
          {isInstalled ? (
            <div className="flex flex-col items-center gap-3 py-4">
              <div className="flex h-12 w-12 items-center justify-center rounded-full bg-accent/10">
                <Check className="h-6 w-6 text-accent" aria-hidden="true" />
              </div>
              <p className="text-sm font-medium">Ứng dụng đã được cài đặt!</p>
              <Button variant="outline" onClick={() => navigate("/")}>
                <ArrowLeft className="mr-2 h-4 w-4" aria-hidden="true" /> Về trang chủ
              </Button>
            </div>
          ) : deferredPrompt ? (
            <Button onClick={() => void handleInstall()} className="w-full" size="lg">
              <Download className="mr-2 h-5 w-5" aria-hidden="true" /> Cài đặt ngay
            </Button>
          ) : (
            <div className="space-y-3 text-sm text-muted-foreground">
              <p className="font-medium text-foreground">Cách cài đặt:</p>
              <div className="flex items-start gap-3">
                <Smartphone className="mt-0.5 h-5 w-5 shrink-0 text-primary" aria-hidden="true" />
                <div>
                  <p className="font-medium text-foreground">Android (Chrome)</p>
                  <p>Mở menu ⋮ → "Thêm vào màn hình chính"</p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <Smartphone className="mt-0.5 h-5 w-5 shrink-0 text-primary" aria-hidden="true" />
                <div>
                  <p className="font-medium text-foreground">iPhone (Safari)</p>
                  <p>Bấm Chia sẻ → "Thêm vào Màn hình chính"</p>
                </div>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
};

export default Install;
