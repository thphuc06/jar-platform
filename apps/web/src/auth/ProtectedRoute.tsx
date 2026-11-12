import { Navigate, Outlet, useLocation } from "react-router-dom";
import PageSpinner from "@/components/PageSpinner";
import { useAuth } from "./useAuth";

/** Everything below this route needs a signed-in user; anyone else is sent to /login and back afterwards. */
const ProtectedRoute = () => {
  const { status } = useAuth();
  const location = useLocation();

  if (status === "loading") return <PageSpinner />;
  if (status === "anonymous") {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
  }
  return <Outlet />;
};

export default ProtectedRoute;
