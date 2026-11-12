import { Bell, Home, Inbox, PiggyBank, Target, User, type LucideIcon } from "lucide-react";

export interface NavItem {
  to: string;
  icon: LucideIcon;
  /** Sidebar label */
  label: string;
  /** Short label for the bottom bar */
  short: string;
  /** Shows the unread-notification badge */
  badge?: boolean;
}

export const NAV_ITEMS: NavItem[] = [
  { to: "/", icon: Home, label: "Tổng quan", short: "Trang chủ" },
  { to: "/inbox", icon: Inbox, label: "Hộp tiền", short: "Hộp tiền" },
  { to: "/jars", icon: PiggyBank, label: "Hũ ngân sách", short: "Hũ" },
  { to: "/goals", icon: Target, label: "Mục tiêu", short: "Mục tiêu" },
  { to: "/alerts", icon: Bell, label: "Cảnh báo", short: "Cảnh báo", badge: true },
  { to: "/profile", icon: User, label: "Hồ sơ", short: "Hồ sơ" },
];
