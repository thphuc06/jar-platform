import { NavLink } from "react-router-dom";
import { Home, Inbox, PiggyBank, Target, User } from "lucide-react";
import { cn } from "@/lib/utils";

const links = [
  { to: "/", icon: Home, label: "Home" },
  { to: "/inbox", icon: Inbox, label: "Inbox" },
  { to: "/jars", icon: PiggyBank, label: "Jars" },
  { to: "/goals", icon: Target, label: "Goals" },
  { to: "/profile", icon: User, label: "Profile" },
];

const BottomNav = () => (
  <nav className="fixed bottom-0 left-0 right-0 z-50 glass border-t md:hidden">
    <div className="flex items-center justify-around h-16">
      {links.map(({ to, icon: Icon, label }) => (
        <NavLink
          key={to}
          to={to}
          end={to === "/"}
          className={({ isActive }) =>
            cn(
              "flex flex-col items-center gap-0.5 px-3 py-1.5 text-[11px] font-medium transition-colors",
              isActive ? "text-primary" : "text-muted-foreground"
            )
          }
        >
          <Icon className="h-5 w-5" />
          {label}
        </NavLink>
      ))}
    </div>
  </nav>
);

export default BottomNav;
