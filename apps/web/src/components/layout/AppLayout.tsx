import { Outlet, useLocation } from "react-router-dom";
import BottomNav from "./BottomNav";
import DesktopSidebar from "./DesktopSidebar";

const AppLayout = () => {
  const location = useLocation();
  const isOnboarding = location.pathname === "/onboarding";

  if (isOnboarding) return <Outlet />;

  return (
    <div className="flex min-h-screen bg-background">
      <DesktopSidebar />
      <main className="flex-1 pb-20 md:pb-0 md:ml-64">
        <div className="max-w-4xl mx-auto px-4 py-6">
          <Outlet />
        </div>
      </main>
      <BottomNav />
    </div>
  );
};

export default AppLayout;
