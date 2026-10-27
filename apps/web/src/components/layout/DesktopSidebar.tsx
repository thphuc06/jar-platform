import { NavLink } from "react-router-dom";
import { Home, Inbox, PiggyBank, Target, User, Shield } from "lucide-react";
import { cn } from "@/lib/utils";
import ThemeToggle from "@/components/ThemeToggle";

const links = [
  { to: "/", icon: Home, label: "Dashboard" },
  { to: "/inbox", icon: Inbox, label: "Money Inbox" },
  { to: "/jars", icon: PiggyBank, label: "Budget Jars" },
  { to: "/goals", icon: Target, label: "Goal Planner" },
  { to: "/profile", icon: User, label: "Profile" },
];

const DesktopSidebar = () => (
  <aside className="hidden md:flex md:flex-col md:w-64 fixed inset-y-0 left-0 z-40 bg-sidebar border-r border-sidebar-border">
    <div className="flex items-center gap-3 px-6 h-16 border-b border-sidebar-border">
      <div className="h-9 w-9 rounded-xl gradient-premium flex items-center justify-center shadow-lg shadow-primary/20">
        <Shield className="h-4.5 w-4.5 text-primary-foreground" />
      </div>
      <div>
        <span className="text-lg font-display font-bold text-sidebar-foreground">Jars</span>
        <p className="text-[10px] text-sidebar-foreground/40 -mt-0.5">6-jar personal finance</p>
      </div>
    </div>
    <nav className="flex-1 px-3 py-4 space-y-1">
      {links.map(({ to, icon: Icon, label }) => (
        <NavLink
          key={to}
          to={to}
          end={to === "/"}
          className={({ isActive }) =>
            cn(
              "flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all duration-200",
              isActive
                ? "bg-sidebar-primary/15 text-sidebar-primary shadow-sm"
                : "text-sidebar-foreground/60 hover:bg-sidebar-accent/60 hover:text-sidebar-foreground"
            )
          }
        >
          <Icon className="h-5 w-5" />
          {label}
        </NavLink>
      ))}
    </nav>
    <div className="px-3 py-4 border-t border-sidebar-border flex items-center justify-between">
      <span className="text-xs text-sidebar-foreground/40 px-3">Theme</span>
      <ThemeToggle />
    </div>
  </aside>
);

export default DesktopSidebar;
