import { Navigate, Outlet } from "react-router-dom";
import { useMe } from "@/api/me";
import { useAuth } from "@/auth/useAuth";
import ErrorNotice from "@/components/ErrorNotice";
import PageSpinner from "@/components/PageSpinner";
import { useNotificationStream } from "@/realtime/useNotificationStream";
import BottomNav from "./BottomNav";
import DesktopSidebar from "./DesktopSidebar";

/** The signed-in area: loads the profile (which creates the user on first use), runs the SSE stream and shows the nav. */
const AppLayout = () => {
  const { status } = useAuth();
  const me = useMe();
  useNotificationStream(status === "authenticated");

  if (me.isPending) return <PageSpinner label="Đang tải hồ sơ..." />;
  if (me.isError) {
    return (
      <div className="mx-auto max-w-md p-6">
        <ErrorNotice error={me.error} what="hồ sơ của bạn" onRetry={() => void me.refetch()} />
      </div>
    );
  }
  // first sign-in: the risk questionnaire comes before everything else
  if (!me.data.onboardingCompleted) return <Navigate to="/onboarding" replace />;

  return (
    <div className="flex min-h-screen bg-background">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-2 focus:top-2 focus:z-[60] focus:rounded-md focus:bg-background focus:px-3 focus:py-2 focus:text-sm focus:shadow"
      >
        Bỏ qua điều hướng
      </a>
      <DesktopSidebar />
      <main id="main" className="flex-1 pb-20 md:ml-64 md:pb-0">
        <div className="mx-auto max-w-4xl px-4 py-6">
          <Outlet />
        </div>
      </main>
      <BottomNav />
    </div>
  );
};

export default AppLayout;
