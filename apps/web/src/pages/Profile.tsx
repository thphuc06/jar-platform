import { useState } from "react";
import { motion } from "framer-motion";
import { Check, ChevronRight, ListChecks, LogOut, Monitor, Moon, Pencil, Shield, Smartphone, Sun, User, X } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useTheme } from "next-themes";
import { useMe, useRiskProfile, useUpdateMe } from "@/api/me";
import { describeError } from "@/api/problem";
import type { Me } from "@/api/types";
import { useAuth } from "@/auth/useAuth";
import ErrorNotice from "@/components/ErrorNotice";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/hooks/use-toast";
import { formatDate } from "@/lib/dates";
import {
  AGE_BANDS,
  ageBandLabel,
  INCOME_SITUATIONS,
  incomeSituationLabel,
  type AgeBand,
  type FactOption,
  type IncomeSituation,
} from "@/lib/profileFacts";
import { riskLevelLabel } from "@/lib/riskQuestionnaire";

const container = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.05 } },
};
const item = {
  hidden: { opacity: 0, y: 8 },
  show: { opacity: 1, y: 0 },
};

const themes = [
  { value: "light", label: "Sáng", icon: Sun },
  { value: "dark", label: "Tối", icon: Moon },
  { value: "system", label: "Theo hệ thống", icon: Monitor },
];

const ProfileCard = ({ me, signInEmail }: { me: Me; signInEmail?: string }) => {
  const { toast } = useToast();
  const update = useUpdateMe();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(me.displayName ?? "");

  const email = me.email || signInEmail || "";
  const shownName = me.displayName || email || "Người dùng";

  const save = (event: React.FormEvent) => {
    event.preventDefault();
    update.mutate(
      { displayName: name.trim() },
      {
        onSuccess: () => {
          setEditing(false);
          toast({ title: "Đã cập nhật", description: "Thông tin cá nhân đã được lưu." });
        },
        onError: (error) => toast({ title: "Không lưu được thông tin", description: describeError(error), variant: "destructive" }),
      },
    );
  };

  return (
    <Card>
      <CardContent className="p-6">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-4">
            <div className="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
              <User className="h-7 w-7 text-primary" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <h2 className="break-words text-lg font-display font-bold">{shownName}</h2>
              {email && <p className="break-all text-sm text-muted-foreground">{email}</p>}
            </div>
          </div>
          {!editing && (
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              onClick={() => {
                setName(me.displayName ?? "");
                setEditing(true);
              }}
            >
              <Pencil className="h-4 w-4" aria-hidden="true" />
              <span className="sr-only">Sửa tên hiển thị</span>
            </Button>
          )}
        </div>

        {editing && (
          <form onSubmit={save} className="mt-4 space-y-3 border-t border-border pt-4">
            <div className="space-y-1.5">
              <Label htmlFor="profile-name" className="text-xs text-muted-foreground">
                Tên hiển thị
              </Label>
              <Input id="profile-name" value={name} onChange={(e) => setName(e.target.value)} className="h-10" maxLength={80} autoFocus />
            </div>
            <div className="flex gap-2 pt-2">
              <Button type="submit" size="sm" className="flex-1 gap-1.5" disabled={update.isPending}>
                <Check className="h-3.5 w-3.5" aria-hidden="true" /> Lưu
              </Button>
              <Button type="button" size="sm" variant="outline" className="flex-1 gap-1.5" onClick={() => setEditing(false)} disabled={update.isPending}>
                <X className="h-3.5 w-3.5" aria-hidden="true" /> Hủy
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
};

/** One radio group of a profile fact, with the same look as the theme chooser. */
function FactChoice<T extends string>({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: readonly FactOption<T>[];
  value: T | null;
  onChange: (value: T) => void;
}) {
  return (
    <div className="space-y-1.5">
      <p className="text-xs text-muted-foreground">{label}</p>
      <div role="radiogroup" aria-label={label} className="space-y-1.5">
        {options.map((o) => (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={value === o.value}
            onClick={() => onChange(o.value)}
            className={`w-full rounded-lg border px-3 py-2 text-left text-sm transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
              value === o.value ? "border-primary bg-primary/5 font-medium" : "border-border hover:border-muted-foreground/30"
            }`}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}

/**
 * Age band and income situation: facts the advisor reads next to the risk profile. Users who answered the
 * questionnaire before these questions existed see them as missing and fill them in here.
 */
const FactsCard = ({ me }: { me: Me }) => {
  const { toast } = useToast();
  const update = useUpdateMe();
  const [editing, setEditing] = useState(false);
  const [age, setAge] = useState<AgeBand | null>(null);
  const [income, setIncome] = useState<IncomeSituation | null>(null);

  const ageLabel = ageBandLabel(me.ageBand);
  const incomeLabel = incomeSituationLabel(me.incomeSituation);
  const missing = !ageLabel || !incomeLabel;

  const startEditing = () => {
    setAge(AGE_BANDS.find((o) => o.value === me.ageBand)?.value ?? null);
    setIncome(INCOME_SITUATIONS.find((o) => o.value === me.incomeSituation)?.value ?? null);
    setEditing(true);
  };

  const save = (event: React.FormEvent) => {
    event.preventDefault();
    if (!age || !income) return;
    update.mutate(
      { ageBand: age, incomeSituation: income },
      {
        onSuccess: () => {
          setEditing(false);
          toast({ title: "Đã cập nhật", description: "Trợ lý sẽ dùng thông tin mới ở tin nhắn tiếp theo." });
        },
        onError: (error) => toast({ title: "Không lưu được thông tin", description: describeError(error), variant: "destructive" }),
      },
    );
  };

  return (
    <Card>
      <CardContent className="p-5">
        <div className="mb-3 flex items-center gap-2">
          <ListChecks className="h-5 w-5 text-primary" aria-hidden="true" />
          <h2 className="font-display font-semibold">Hoàn cảnh của bạn</h2>
        </div>
        {!editing && (
          <>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <p className="text-xs text-muted-foreground">Độ tuổi</p>
                <p className={ageLabel ? "font-medium" : "text-muted-foreground"}>{ageLabel ?? "Chưa cung cấp"}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Thu nhập</p>
                <p className={incomeLabel ? "font-medium" : "text-muted-foreground"}>{incomeLabel ?? "Chưa cung cấp"}</p>
              </div>
            </div>
            {missing && (
              <p className="mt-3 text-xs text-muted-foreground">Bổ sung để trợ lý chọn sản phẩm phù hợp với hoàn cảnh của bạn.</p>
            )}
            <Button variant="outline" size="sm" className="mt-4 w-full" onClick={startEditing}>
              {missing ? "Bổ sung thông tin" : "Sửa thông tin"}
            </Button>
          </>
        )}
        {editing && (
          <form onSubmit={save} className="space-y-4">
            <FactChoice label="Độ tuổi" options={AGE_BANDS} value={age} onChange={setAge} />
            <FactChoice label="Thu nhập" options={INCOME_SITUATIONS} value={income} onChange={setIncome} />
            <div className="flex gap-2 pt-1">
              <Button type="submit" size="sm" className="flex-1 gap-1.5" disabled={!age || !income || update.isPending}>
                <Check className="h-3.5 w-3.5" aria-hidden="true" /> Lưu
              </Button>
              <Button type="button" size="sm" variant="outline" className="flex-1 gap-1.5" onClick={() => setEditing(false)} disabled={update.isPending}>
                <X className="h-3.5 w-3.5" aria-hidden="true" /> Hủy
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
};

const RiskCard = () => {
  const navigate = useNavigate();
  const risk = useRiskProfile();

  return (
    <Card className="border-primary/20">
      <CardContent className="p-5">
        <div className="mb-3 flex items-center gap-2">
          <Shield className="h-5 w-5 text-primary" aria-hidden="true" />
          <h2 className="font-display font-semibold">Hồ sơ rủi ro</h2>
        </div>
        {risk.isPending && <Skeleton className="h-12 w-full" />}
        {risk.isError && <ErrorNotice error={risk.error} what="hồ sơ rủi ro" onRetry={() => void risk.refetch()} />}
        {risk.data === null && <p className="text-sm text-muted-foreground">Bạn chưa làm bảng câu hỏi khẩu vị rủi ro.</p>}
        {risk.data && (
          <div className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <p className="text-xs text-muted-foreground">Khẩu vị rủi ro</p>
              <p className="font-medium">{riskLevelLabel(risk.data.riskLevel)}</p>
            </div>
            <div>
              <p className="text-xs text-muted-foreground">Điểm</p>
              <p className="font-medium">{risk.data.score}/100</p>
            </div>
            <div className="col-span-2">
              <p className="text-xs text-muted-foreground">Đánh giá lần cuối</p>
              <p className="font-medium">{formatDate(risk.data.assessedAt)}</p>
            </div>
          </div>
        )}
        {!risk.isPending && !risk.isError && (
          <Button variant="outline" size="sm" className="mt-4 w-full" onClick={() => navigate("/onboarding")}>
            {risk.data ? "Làm lại bảng câu hỏi" : "Làm bảng câu hỏi"}
          </Button>
        )}
      </CardContent>
    </Card>
  );
};

const Profile = () => {
  const navigate = useNavigate();
  const { theme, setTheme } = useTheme();
  const { devMode, user, signOut } = useAuth();
  const me = useMe();

  return (
    <motion.div variants={container} initial="hidden" animate="show" className="space-y-6">
      <motion.div variants={item}>
        <h1 className="text-2xl font-display font-bold">Hồ sơ</h1>
        <p className="text-sm text-muted-foreground">Quản lý tài khoản và cài đặt</p>
      </motion.div>

      <motion.div variants={item}>
        {me.isPending && <Skeleton className="h-28 w-full rounded-xl" />}
        {me.isError && <ErrorNotice error={me.error} what="thông tin tài khoản" onRetry={() => void me.refetch()} />}
        {me.data && <ProfileCard me={me.data} signInEmail={user?.email} />}
      </motion.div>

      <motion.div variants={item}>
        <Card>
          <CardContent className="p-5">
            <h2 className="mb-3 font-display text-sm font-semibold">Giao diện</h2>
            <div role="radiogroup" aria-label="Giao diện" className="grid grid-cols-3 gap-2">
              {themes.map((t) => (
                <button
                  key={t.value}
                  type="button"
                  role="radio"
                  aria-checked={theme === t.value}
                  onClick={() => setTheme(t.value)}
                  className={`flex flex-col items-center gap-2 rounded-xl border-2 p-3 transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                    theme === t.value ? "border-primary bg-primary/5 shadow-sm" : "border-border hover:border-muted-foreground/30"
                  }`}
                >
                  <t.icon className={`h-5 w-5 ${theme === t.value ? "text-primary" : "text-muted-foreground"}`} aria-hidden="true" />
                  <span className={`text-xs font-medium ${theme === t.value ? "text-primary" : "text-muted-foreground"}`}>{t.label}</span>
                </button>
              ))}
            </div>
          </CardContent>
        </Card>
      </motion.div>

      <motion.div variants={item}>
        <RiskCard />
      </motion.div>

      <motion.div variants={item}>{me.data && <FactsCard me={me.data} />}</motion.div>

      <motion.div variants={item}>
        <Card>
          <CardContent className="p-0">
            <button
              type="button"
              onClick={() => navigate("/install")}
              className="flex w-full items-center gap-3 px-4 py-3.5 text-left transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-secondary">
                <Smartphone className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium">Cài đặt ứng dụng</p>
                <p className="text-xs text-muted-foreground">Thêm FinAdvisor vào màn hình chính</p>
              </div>
              <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            </button>
          </CardContent>
        </Card>
      </motion.div>

      <motion.div variants={item}>
        {import.meta.env.DEV && devMode ? (
          <p className="rounded-lg border border-warning/30 bg-warning/10 p-3 text-center text-xs text-warning">
            Chế độ dev-token ({user?.sub}): không có đăng nhập. Bỏ VITE_DEV_TOKEN_SUB để dùng Cognito.
          </p>
        ) : (
          <Button
            variant="outline"
            className="w-full gap-2 text-destructive hover:bg-destructive/5 hover:text-destructive"
            onClick={() => void signOut()}
          >
            <LogOut className="h-4 w-4" aria-hidden="true" /> Đăng xuất
          </Button>
        )}
      </motion.div>
    </motion.div>
  );
};

export default Profile;
