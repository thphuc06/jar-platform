import { Link } from "react-router-dom";

const NotFound = () => (
  <div className="flex min-h-screen items-center justify-center bg-muted">
    <div className="text-center">
      <h1 className="mb-4 text-4xl font-bold">404</h1>
      <p className="mb-4 text-xl text-muted-foreground">Không tìm thấy trang này</p>
      <Link to="/" className="text-primary underline hover:text-primary/90">
        Về trang chủ
      </Link>
    </div>
  </div>
);

export default NotFound;
