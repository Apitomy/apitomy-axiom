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
import SunIcon from "@patternfly/react-icons/dist/esm/icons/sun-icon";
import MoonIcon from "@patternfly/react-icons/dist/esm/icons/moon-icon";
import DesktopIcon from "@patternfly/react-icons/dist/esm/icons/desktop-icon";
import { type ThemeMode } from "../hooks/useTheme";

interface AppMastheadProps {
    engineName?: string;
    appVersion: string;
    themeMode: ThemeMode;
    setThemeMode: (mode: ThemeMode) => void;
}

const THEME_CYCLE: ThemeMode[] = ["light", "dark", "system"];

/**
 * Returns the icon and tooltip label for the current theme mode.
 */
function themeDisplay(mode: ThemeMode) {
    switch (mode) {
        case "light":
            return { icon: <SunIcon />, label: "Theme: Light (click for Dark)" };
        case "dark":
            return { icon: <MoonIcon />, label: "Theme: Dark (click for System)" };
        case "system":
            return { icon: <DesktopIcon />, label: "Theme: System (click for Light)" };
    }
}

export function AppMasthead({ engineName, appVersion, themeMode, setThemeMode }: AppMastheadProps) {
    const navigate = useNavigate();
    const [isAboutOpen, setIsAboutOpen] = useState(false);
    const { icon: themeIcon, label: themeLabel } = themeDisplay(themeMode);

    const cycleTheme = () => {
        const currentIndex = THEME_CYCLE.indexOf(themeMode);
        const nextIndex = (currentIndex + 1) % THEME_CYCLE.length;
        setThemeMode(THEME_CYCLE[nextIndex]);
    };

    return (
        <>
            <Masthead className="axiom-masthead">
                <MastheadMain>
                    <MastheadBrand>
                        <span
                            className="axiom-masthead__brand-text"
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
                                            <RobotIcon className="axiom-masthead__icon" style={{ transform: "scale(1.25)" }} />
                                        </Button>
                                    </Tooltip>
                                )}
                            </ToolbarItem>
                            <ToolbarItem>
                                <Tooltip content={themeLabel}>
                                    <Button variant="plain" aria-label="Toggle theme"
                                        onClick={cycleTheme}>
                                        <span className="axiom-masthead__icon">{themeIcon}</span>
                                    </Button>
                                </Tooltip>
                            </ToolbarItem>
                            <ToolbarItem>
                                <Tooltip content="About Axiom">
                                    <Button variant="plain" aria-label="About"
                                        onClick={() => setIsAboutOpen(true)}>
                                        <QuestionCircleIcon className="axiom-masthead__icon" />
                                    </Button>
                                </Tooltip>
                            </ToolbarItem>
                        </ToolbarContent>
                    </Toolbar>
                </MastheadContent>
                <div className="axiom-masthead__gradient-bar" />
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
                            : engineName === "copilot" ? "GitHub Copilot CLI"
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
