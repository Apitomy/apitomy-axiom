import { useState, useEffect, useCallback } from "react";

export type ThemeMode = "light" | "dark" | "system";
export type EffectiveTheme = "light" | "dark";

const STORAGE_KEY = "axiom.theme";
const DARK_CLASS = "pf-v6-theme-dark";

function getSystemPreference(): EffectiveTheme {
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function resolveTheme(mode: ThemeMode): EffectiveTheme {
    return mode === "system" ? getSystemPreference() : mode;
}

function applyTheme(effective: EffectiveTheme): void {
    const root = document.documentElement;
    if (effective === "dark") {
        root.classList.add(DARK_CLASS);
    } else {
        root.classList.remove(DARK_CLASS);
    }
}

function loadStoredMode(): ThemeMode {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === "light" || stored === "dark" || stored === "system") {
        return stored;
    }
    return "system";
}

/**
 * Manages the application color theme (light, dark, or system-following).
 * Persists the user's choice to localStorage and toggles PatternFly's
 * dark-mode class on the document element.
 */
export function useTheme() {
    const [mode, setModeState] = useState<ThemeMode>(loadStoredMode);
    const [effectiveTheme, setEffectiveTheme] = useState<EffectiveTheme>(() => resolveTheme(loadStoredMode()));

    const setMode = useCallback((newMode: ThemeMode) => {
        localStorage.setItem(STORAGE_KEY, newMode);
        setModeState(newMode);
        const effective = resolveTheme(newMode);
        setEffectiveTheme(effective);
        applyTheme(effective);
    }, []);

    useEffect(() => {
        applyTheme(resolveTheme(mode));

        const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
        const handleChange = () => {
            if (mode === "system") {
                const effective = getSystemPreference();
                setEffectiveTheme(effective);
                applyTheme(effective);
            }
        };
        mediaQuery.addEventListener("change", handleChange);
        return () => mediaQuery.removeEventListener("change", handleChange);
    }, [mode]);

    return { mode, effectiveTheme, setMode };
}
