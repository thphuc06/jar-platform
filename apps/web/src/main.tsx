import "@fontsource-variable/plus-jakarta-sans";
import "@fontsource-variable/space-grotesk";
import { createRoot } from "react-dom/client";
import { ThemeProvider } from "next-themes";
import App from "./App";
import { configureAuth } from "./auth/amplify";
import "./index.css";

// before the first render: on /auth/callback?code=... this completes the sign-in
configureAuth();

createRoot(document.getElementById("root")!).render(
  <ThemeProvider attribute="class" defaultTheme="light" enableSystem>
    <App />
  </ThemeProvider>,
);
