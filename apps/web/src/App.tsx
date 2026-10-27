import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { lazy, Suspense } from "react";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { ApiError } from "@/api";
import RequireAuth from "@/auth/RequireAuth";
import AppLayout from "@/components/layout/AppLayout";
import { Toaster as Sonner } from "@/components/ui/sonner";
import { Toaster } from "@/components/ui/toaster";
import { TooltipProvider } from "@/components/ui/tooltip";
import AuthCallback from "./pages/AuthCallback";
import ComingSoon from "./pages/ComingSoon";
import Install from "./pages/Install";
import NotFound from "./pages/NotFound";

// one chunk per screen: the charts library (recharts) is only downloaded when the Dashboard is opened
const Dashboard = lazy(() => import("./pages/Dashboard"));
const MoneyInbox = lazy(() => import("./pages/MoneyInbox"));
const Onboarding = lazy(() => import("./pages/Onboarding"));
const Profile = lazy(() => import("./pages/Profile"));

const PageLoading = () => (
  <div className="flex justify-center py-16">
    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-label="Loading" />
  </div>
);

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // a 4xx will not change by asking again (404, 422, 401 with an expired session); 5xx/network get 2 retries
      retry: (failures, error) => !(error instanceof ApiError && error.status < 500) && failures < 2,
    },
  },
});

const App = () => (
  <QueryClientProvider client={queryClient}>
    <TooltipProvider>
      <Toaster />
      <Sonner />
      <BrowserRouter>
        <Suspense fallback={<PageLoading />}>
        <Routes>
          <Route path="/auth/callback" element={<AuthCallback />} />
          <Route path="/install" element={<Install />} />
          <Route element={<RequireAuth />}>
            <Route path="/onboarding" element={<Onboarding />} />
            <Route element={<AppLayout />}>
              <Route path="/" element={<Dashboard />} />
              <Route path="/inbox" element={<MoneyInbox />} />
              <Route path="/jars" element={<ComingSoon title="Budget Jars" step="P1.11c" />} />
              <Route path="/goals" element={<ComingSoon title="Goals" step="P1.11c" />} />
              <Route path="/transfer" element={<ComingSoon title="Transfer" step="P1.11c" />} />
              <Route path="/profile" element={<Profile />} />
            </Route>
          </Route>
          <Route path="*" element={<NotFound />} />
        </Routes>
        </Suspense>
      </BrowserRouter>
    </TooltipProvider>
  </QueryClientProvider>
);

export default App;
