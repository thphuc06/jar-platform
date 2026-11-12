import { NavLink } from "react-router-dom";
import { Shield } from "lucide-react";
import { cn } from "@/lib/utils";
import ThemeToggle from "@/components/ThemeToggle";
import { useAuth } from "@/auth/useAuth";
import { NAV_ITEMS } from "./nav";
import UnreadBadge from "./UnreadBadge";

const DesktopSidebar = () => {
  const { devMode, user } = useAuth();

  return (
    <aside className="fixed inset-y-0 left-0 z-40 hidden border-r border-sidebar-border bg-sidebar md:flex md:w-64 md:flex-col">
      <div className="flex h-16 items-center gap-3 border-b border-sidebar-border px-6">
        <div className="flex h-9 w-9 items-center justify-center rounded-xl gradient-premium shadow-lg shadow-primary/20">
          <Shield className="h-4 w-4 text-primary-foreground" aria-hidden="true" />
        </div>
        <div>
          <span className="text-lg font-display font-bold text-sidebar-foreground">FinAdvisor</span>
          <p className="-mt-0.5 text-[10px] text-sidebar-foreground/60">Quản lý theo 6 chiếc hũ</p>
        </div>
      </div>
      <nav aria-label="Điều hướng chính" className="flex-1 space-y-1 px-3 py-4">
        {NAV_ITEMS.map(({ to, icon: Icon, label, badge }) => (
          <NavLink
            key={to}
            to={to}
            end={to === "/"}
            className={({ isActive }) =>
              cn(
                "flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring",
                isActive
                  ? "bg-sidebar-primary/15 text-sidebar-primary shadow-sm"
                  : "text-sidebar-foreground/70 hover:bg-sidebar-accent/60 hover:text-sidebar-foreground",
              )
            }
          >
            <Icon className="h-5 w-5" aria-hidden="true" />
            {label}
            {badge && <UnreadBadge className="ml-auto" />}
          </NavLink>
        ))}
      </nav>
      <div className="space-y-2 border-t border-sidebar-border px-3 py-4">
        {import.meta.env.DEV && devMode && (
          <p className="rounded-lg bg-warning/15 px-3 py-1.5 text-[11px] text-warning">
            Chế độ dev-token: {user?.sub}
          </p>
        )}
        <div className="flex items-center justify-between">
          <span className="px-3 text-xs text-sidebar-foreground/60">Giao diện</span>
          <ThemeToggle />
        </div>
      </div>
    </aside>
  );
};

export default DesktopSidebar;
