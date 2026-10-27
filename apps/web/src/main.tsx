import "@fontsource-variable/plus-jakarta-sans";
import "@fontsource-variable/space-grotesk";
import { ThemeProvider } from "next-themes";
import { createRoot } from "react-dom/client";
import { AuthProvider } from "react-oidc-context";
import { userManager } from "@/auth/oidc";
import App from "./App.tsx";
import "./index.css";

/** Removes ?code=...&state=... from the address bar once the code has been exchanged (a code works only once). */
const onSigninCallback = () => window.history.replaceState({}, document.title, window.location.pathname);

createRoot(document.getElementById("root")!).render(
  <ThemeProvider attribute="class" defaultTheme="light" enableSystem>
    <AuthProvider userManager={userManager} onSigninCallback={onSigninCallback}>
      <App />
    </AuthProvider>
  </ThemeProvider>,
);
