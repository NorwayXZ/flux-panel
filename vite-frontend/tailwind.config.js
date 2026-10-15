import {heroui} from "@heroui/theme"

// Visual system adapted from NodePassDash (BSD-3-Clause, see THIRD_PARTY_NOTICES.md):
// HeroUI with a Tailwind blue primary and the Inter typeface.
const primary = {
  50: "#eff6ff",
  100: "#dbeafe",
  200: "#bfdbfe",
  300: "#93c5fd",
  400: "#60a5fa",
  500: "#3b82f6",
  600: "#2563eb",
  700: "#1d4ed8",
  800: "#1e40af",
  900: "#1e3a8a",
  950: "#172554",
  DEFAULT: "#3b82f6",
  foreground: "#ffffff",
}

/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    './src/layouts/**/*.{js,ts,jsx,tsx,mdx}',
    './src/pages/**/*.{js,ts,jsx,tsx,mdx}',
    './src/components/**/*.{js,ts,jsx,tsx,mdx}',
    "./node_modules/@heroui/theme/dist/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ["'Inter Variable'", "Inter", "ui-sans-serif", "system-ui", "-apple-system", "'PingFang SC'", "'Microsoft YaHei'", "sans-serif"],
      },
      keyframes: {
        "page-enter": {
          from: { opacity: "0", transform: "translateY(8px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
        float: {
          "0%, 100%": { transform: "translateY(0px)" },
          "50%": { transform: "translateY(-10px)" },
        },
      },
      animation: {
        "page-enter": "page-enter 0.32s cubic-bezier(0.22, 1, 0.36, 1) both",
        float: "float 3s ease-in-out infinite",
      },
    },
  },
  darkMode: "class",
  plugins: [heroui({
    themes: {
      light: { colors: { primary } },
      dark: { colors: { primary } },
    },
  })],
}
