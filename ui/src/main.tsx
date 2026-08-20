import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { App } from "./App";

import "@patternfly/patternfly/patternfly.css";
import "@xyflow/react/dist/style.css";
import "@apitomy/flow-ui/style.css";
import "./axiom-theme.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
        <BrowserRouter>
            <App />
        </BrowserRouter>
    </React.StrictMode>
);
