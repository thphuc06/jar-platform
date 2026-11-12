import { useState } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowRight, Loader2, PiggyBank, Shield } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { safeReturnPath } from "@/auth/config";
import { useAuth } from "@/auth/useAuth";
import PageSpinner from "@/components/PageSpinner";

const Login = () => {
  const { status, error, signIn } = useAuth();
  const location = useLocation();
  const [redirecting, setRedirecting] = useState(false);
  const from = safeReturnPath((location.state as { from?: string } | null)?.from);

  if (status === "loading") return <PageSpinner />;
  if (status === "authenticated") return <Navigate to={from} replace />;

  const handleSignIn = async () => {
    setRedirecting(true);
    try {
      await signIn(from);
    } finally {
      // only reached when the redirect did not happen (configuration error)
      setRedirecting(false);
    }
  };

  return (
    <div className="flex min-h-screen bg-background">
      <div className="relative hidden items-center justify-center overflow-hidden p-12 gradient-premium lg:flex lg:w-1/2">
        <div className="absolute inset-0 opacity-10" aria-hidden="true">
          <div className="absolute left-20 top-20 h-64 w-64 rounded-full bg-primary-foreground/20 blur-3xl" />
          <div className="absolute bottom-20 right-20 h-48 w-48 rounded-full bg-primary-foreground/15 blur-3xl" />
          <div className="absolute left-1/2 top-1/2 h-96 w-96 -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary-foreground/10 blur-3xl" />
        </div>
        <motion.div
          initial={{ opacity: 0, x: -30 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ duration: 0.7 }}
          className="relative z-10 max-w-md text-primary-foreground"
        >
          <div className="mb-8 flex h-16 w-16 items-center justify-center rounded-2xl bg-primary-foreground/20 backdrop-blur-sm">
            <PiggyBank className="h-8 w-8 text-primary-foreground" aria-hidden="true" />
          </div>
          <h2 className="mb-4 text-4xl font-display font-bold">Quản lý tiền theo 6 chiếc hũ</h2>
          <p className="text-lg leading-relaxed text-primary-foreground/80">
            Chia thu nhập vào các hũ, ghi lại mọi khoản thu chi, theo dõi mục tiêu và nhận cảnh báo khi chi tiêu bất thường.
          </p>
        </motion.div>
      </div>

      <div className="flex flex-1 flex-col items-center justify-center px-6">
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5 }}
          className="w-full max-w-sm space-y-8"
        >
          <div className="space-y-3 text-center">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl shadow-xl shadow-primary/20 gradient-premium">
              <Shield className="h-7 w-7 text-primary-foreground" aria-hidden="true" />
            </div>
            <div>
              <h1 className="text-2xl font-display font-bold">Đăng nhập</h1>
              <p className="text-sm text-muted-foreground">Chào mừng bạn đến với FinAdvisor</p>
            </div>
          </div>

          <Card className="border-0 shadow-xl shadow-black/5 dark:shadow-black/20">
            <CardContent className="space-y-4 p-6">
              {error && (
                <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
                  {error}
                </p>
              )}
              <Button
                type="button"
                size="lg"
                className="h-11 w-full gap-2 border-0 shadow-md shadow-primary/20 gradient-premium"
                onClick={() => void handleSignIn()}
                disabled={redirecting}
              >
                {redirecting ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : null}
                {redirecting ? "Đang chuyển đến trang đăng nhập..." : "Đăng nhập hoặc đăng ký"}
                {!redirecting && <ArrowRight className="h-4 w-4" aria-hidden="true" />}
              </Button>
              <p className="text-center text-xs text-muted-foreground">
                Bạn sẽ được chuyển đến trang đăng nhập an toàn của Amazon Cognito. Nếu chưa có tài khoản, hãy chọn Đăng ký ở đó. Ứng dụng không lưu mật khẩu của bạn.
              </p>
            </CardContent>
          </Card>
        </motion.div>
      </div>
    </div>
  );
};

export default Login;
