import { useState } from "react";
import { useNavigate } from "react-router-dom";
import "./AppMasthead.css";
import {
    AboutModal,
    Button,
    Content,
    Masthead,
    MastheadBrand,
    MastheadContent,
    MastheadMain,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
    Tooltip,
} from "@patternfly/react-core";
import QuestionCircleIcon from "@patternfly/react-icons/dist/esm/icons/question-circle-icon";
import RobotIcon from "@patternfly/react-icons/dist/esm/icons/robot-icon";

interface AppMastheadProps {
    engineName?: string;
    appVersion: string;
}

export function AppMasthead({ engineName, appVersion }: AppMastheadProps) {
    const navigate = useNavigate();
    const [isAboutOpen, setIsAboutOpen] = useState(false);

    return (
        <>
            <Masthead style={{
                position: "relative",
                borderBottom: "none",
                boxShadow: "none",
                background: "white",
                marginBottom: "6px",
            }}>
                <MastheadMain>
                    <MastheadBrand>
                        <span
                            style={{ fontSize: "20px", fontWeight: 600, color: "#0b2545", cursor: "pointer", letterSpacing: "-0.5px" }}
                            onClick={() => navigate("/")}>Apitomy Axiom</span>
                    </MastheadBrand>
                </MastheadMain>
                <MastheadContent>
                    <Toolbar>
                        <ToolbarContent>
                            <ToolbarItem align={{ default: "alignEnd" }}>
                                {engineName === "claude-code" && (
                                    <Tooltip content="AI Assistant">
                                        <Button variant="plain" aria-label="AI Assistant"
                                            onClick={() => {
                                                localStorage.setItem("axiom.assistant.discovered", "true");
                                                navigate("/assistant");
                                            }}
                                            className={
                                                localStorage.getItem("axiom.assistant.discovered")
                                                    ? undefined : "axiom-assistant-throb"
                                            }>
                                            <RobotIcon style={{ color: "#2082a3", transform: "scale(1.25)" }} />
                                        </Button>
                                    </Tooltip>
                                )}
                            </ToolbarItem>
                            <ToolbarItem>
                                <Tooltip content="About Axiom">
                                    <Button variant="plain" aria-label="About"
                                        onClick={() => setIsAboutOpen(true)}>
                                        <QuestionCircleIcon style={{ color: "#2082a3" }} />
                                    </Button>
                                </Tooltip>
                            </ToolbarItem>
                        </ToolbarContent>
                    </Toolbar>
                </MastheadContent>
                <div style={{
                    position: "absolute",
                    bottom: 0,
                    left: 0,
                    right: 0,
                    height: "3px",
                    background: "linear-gradient(90deg, #0b2545, #1b6b93, #4fc0d0)",
                }} />
            </Masthead>

            <AboutModal
                isOpen={isAboutOpen}
                onClose={() => setIsAboutOpen(false)}
                productName="Apitomy Axiom"
                brandImageSrc="/logo.png"
                brandImageAlt="Apitomy Axiom"
                trademark="Copyright &copy; 2025-2026"
            >
                <Content component="dl">
                    <dt>Version</dt>
                    <dd>{appVersion || "—"}</dd>
                    <dt>AI Engine</dt>
                    <dd>
                        {engineName === "opencode" ? "OpenCode"
                            : engineName === "claude-code" ? "Claude Code"
                            : engineName || "—"}
                    </dd>
                    <dt>License</dt>
                    <dd>Apache License 2.0</dd>
                    <dt>Source</dt>
                    <dd>
                        <a href="https://github.com/Apitomy/apitomy-axiom"
                            target="_blank" rel="noopener noreferrer">
                            github.com/Apitomy/apitomy-axiom
                        </a>
                    </dd>
                </Content>
            </AboutModal>
        </>
    );
}