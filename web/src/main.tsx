import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { ApiClient } from "./api/client";
import { claimToken } from "./api/session";
import "./styles.css";

const token = claimToken();
const client = token ? new ApiClient(token) : null;

const container = document.getElementById("root");
if (!container) throw new Error("root element is missing from index.html");

createRoot(container).render(
  <StrictMode>
    <App client={client} />
  </StrictMode>,
);
