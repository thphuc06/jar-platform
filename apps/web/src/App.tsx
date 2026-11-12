import { lazy, Suspense } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Toaster } from "@/components/ui/toaster";
import { createQueryClient } from "@/api/queryClient";
import { AuthProvider } from "@/auth/AuthProvider";
import ProtectedRoute from "@/auth/ProtectedRoute";
import AppLayout from "@/components/layout/AppLayout";
import PageSpinner from "@/components/PageSpinner";

const Login = lazy(() => import("./pages/Login"));
const AuthCallback = lazy(() => import("./pages/AuthCallback"));
const Install = lazy(() => import("./pages/Install"));
const Onboarding = lazy(() => import("./pages/Onboarding"));
const Index = lazy(() => import("./pages/Index"));
const MoneyInbox = lazy(() => import("./pages/MoneyInbox"));
const BudgetJars = lazy(() => import("./pages/BudgetJars"));
const GoalPlanner = lazy(() => import("./pages/GoalPlanner"));
const Transfer = lazy(() => import("./pages/Transfer"));
const Alerts = lazy(() => import("./pages/Alerts"));
const Profile = lazy(() => import("./pages/Profile"));
const NotFound = lazy(() => import("./pages/NotFound"));

const queryClient = createQueryClient();

const App = () => (
  <QueryClientProvider client={queryClient}>
    <Toaster />
    <BrowserRouter>
      <AuthProvider>
        <Suspense fallback={<PageSpinner />}>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/auth/callback" element={<AuthCallback />} />
            <Route path="/install" element={<Install />} />
            <Route element={<ProtectedRoute />}>
              <Route path="/onboarding" element={<Onboarding />} />
              <Route element={<AppLayout />}>
                <Route path="/" element={<Index />} />
                <Route path="/inbox" element={<MoneyInbox />} />
                <Route path="/jars" element={<BudgetJars />} />
                <Route path="/goals" element={<GoalPlanner />} />
                <Route path="/alerts" element={<Alerts />} />
                <Route path="/transfer" element={<Transfer />} />
                <Route path="/profile" element={<Profile />} />
              </Route>
            </Route>
            <Route path="*" element={<NotFound />} />
          </Routes>
        </Suspense>
      </AuthProvider>
    </BrowserRouter>
  </QueryClientProvider>
);

export default App;
