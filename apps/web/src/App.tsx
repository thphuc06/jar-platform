import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
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
import Onboarding from "./pages/Onboarding";
import Profile from "./pages/Profile";

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
        <Routes>
          <Route path="/auth/callback" element={<AuthCallback />} />
          <Route path="/install" element={<Install />} />
          <Route element={<RequireAuth />}>
            <Route path="/onboarding" element={<Onboarding />} />
            <Route element={<AppLayout />}>
              <Route path="/" element={<ComingSoon title="Dashboard" step="P1.11b" />} />
              <Route path="/inbox" element={<ComingSoon title="Money Inbox" step="P1.11b" />} />
              <Route path="/jars" element={<ComingSoon title="Budget Jars" step="P1.11c" />} />
              <Route path="/goals" element={<ComingSoon title="Goals" step="P1.11c" />} />
              <Route path="/transfer" element={<ComingSoon title="Transfer" step="P1.11c" />} />
              <Route path="/profile" element={<Profile />} />
            </Route>
          </Route>
          <Route path="*" element={<NotFound />} />
        </Routes>
      </BrowserRouter>
    </TooltipProvider>
  </QueryClientProvider>
);

export default App;
