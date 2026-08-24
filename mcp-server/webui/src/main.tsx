import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import "bootstrap/dist/css/bootstrap.min.css";
import "./bootstrap-theme.css";
import "./fonts.css";
import { App } from "./App";

const root = document.getElementById("root");
if (!root) throw new Error("Application root element is missing");

createRoot(root).render(
  <BrowserRouter>
    <App />
  </BrowserRouter>,
);
