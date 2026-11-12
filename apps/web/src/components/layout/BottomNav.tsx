import { NavLink } from "react-router-dom";
import { cn } from "@/lib/utils";
import { NAV_ITEMS } from "./nav";
import UnreadBadge from "./UnreadBadge";

const BottomNav = () => (
  <nav aria-label="Điều hướng chính" className="fixed bottom-0 left-0 right-0 z-50 glass border-t md:hidden">
    <div className="flex h-16 items-center justify-around pb-[env(safe-area-inset-bottom)]">
      {NAV_ITEMS.filter((item) => !item.desktopOnly).map(({ to, icon: Icon, short, badge }) => (
        <NavLink
          key={to}
          to={to}
          end={to === "/"}
          className={({ isActive }) =>
            cn(
              "flex flex-col items-center gap-0.5 rounded-md px-3 py-1.5 text-[11px] font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              isActive ? "text-primary" : "text-muted-foreground",
            )
          }
        >
          <span className="relative">
            <Icon className="h-5 w-5" aria-hidden="true" />
            {badge && <UnreadBadge className="absolute -right-2 -top-1.5" />}
          </span>
          {short}
        </NavLink>
      ))}
    </div>
  </nav>
);

export default BottomNav;
