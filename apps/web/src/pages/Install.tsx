import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Download, Smartphone, Check, ArrowLeft } from "lucide-react";
import { useNavigate } from "react-router-dom";

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

const Install = () => {
  const navigate = useNavigate();
  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [isInstalled, setIsInstalled] = useState(false);

  useEffect(() => {
    const handler = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e as BeforeInstallPromptEvent);
    };
    window.addEventListener("beforeinstallprompt", handler);

    if (window.matchMedia("(display-mode: standalone)").matches) {
      setIsInstalled(true);
    }

    return () => window.removeEventListener("beforeinstallprompt", handler);
  }, []);

  const handleInstall = async () => {
    if (!deferredPrompt) return;
    await deferredPrompt.prompt();
    const { outcome } = await deferredPrompt.userChoice;
    if (outcome === "accepted") {
      setIsInstalled(true);
    }
    setDeferredPrompt(null);
  };

  return (
    <div className="min-h-screen bg-background flex items-center justify-center p-4">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center">
          <img src="/pwa-192x192.png" alt="Jars" className="w-20 h-20 rounded-2xl mx-auto mb-4" />
          <CardTitle className="text-2xl font-display">Install Jars</CardTitle>
          <p className="text-muted-foreground text-sm mt-2">
            Install the app to your home screen for the best experience
          </p>
        </CardHeader>
        <CardContent className="space-y-4">
          {isInstalled ? (
            <div className="flex flex-col items-center gap-3 py-4">
              <div className="h-12 w-12 rounded-full bg-accent/10 flex items-center justify-center">
                <Check className="h-6 w-6 text-accent" />
              </div>
              <p className="text-sm font-medium">App has been installed!</p>
              <Button variant="outline" onClick={() => navigate("/")}>
                <ArrowLeft className="h-4 w-4 mr-2" /> Back to Dashboard
              </Button>
            </div>
          ) : deferredPrompt ? (
            <Button onClick={handleInstall} className="w-full" size="lg">
              <Download className="h-5 w-5 mr-2" /> Install Now
            </Button>
          ) : (
            <div className="space-y-3 text-sm text-muted-foreground">
              <p className="font-medium text-foreground">How to install:</p>
              <div className="flex items-start gap-3">
                <Smartphone className="h-5 w-5 mt-0.5 shrink-0 text-primary" />
                <div>
                  <p className="font-medium text-foreground">Android (Chrome)</p>
                  <p>Tap menu ⋮ → "Add to Home screen"</p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <Smartphone className="h-5 w-5 mt-0.5 shrink-0 text-primary" />
                <div>
                  <p className="font-medium text-foreground">iPhone (Safari)</p>
                  <p>Tap Share → "Add to Home Screen"</p>
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