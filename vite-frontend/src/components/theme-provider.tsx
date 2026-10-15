import React, { useEffect, useState } from "react";
import { useTheme } from "@heroui/use-theme";

export type ThemePreference = "system" | "light" | "dark";

const STORAGE_KEY = "cloudnest.theme";
const CHANGE_EVENT = "cloudnest-theme-change";

export const getThemePreference = (): ThemePreference => {
  try {
    const value = localStorage.getItem(STORAGE_KEY);

    return value === "light" || value === "dark" ? value : "system";
  } catch {
    return "system";
  }
};

export const setThemePreference = (preference: ThemePreference) => {
  try {
    if (preference === "system") localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, preference);
  } catch {
    // Storage may be unavailable; the choice still applies to this page.
  }
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: preference }));
};

/** Current preference, updated when any component changes it. */
export const useThemePreference = (): ThemePreference => {
  const [preference, setPreference] = useState(getThemePreference);

  useEffect(() => {
    const onChange = (event: Event) =>
      setPreference((event as CustomEvent<ThemePreference>).detail);

    window.addEventListener(CHANGE_EVENT, onChange);

    return () => window.removeEventListener(CHANGE_EVENT, onChange);
  }, []);

  return preference;
};

interface ThemeProviderProps {
  children: React.ReactNode;
}

/** Applies the chosen theme; "system" follows the operating system. */
export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
  const { setTheme } = useTheme();
  const preference = useThemePreference();

  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const apply = () => {
      const resolved =
        preference === "system" ? (media.matches ? "dark" : "light") : preference;

      setTheme(resolved);
      document.documentElement.classList.toggle("dark", resolved === "dark");
      document.documentElement.style.colorScheme = resolved;
    };

    apply();
    media.addEventListener("change", apply);

    return () => media.removeEventListener("change", apply);
  }, [preference, setTheme]);

  return <>{children}</>;
};
