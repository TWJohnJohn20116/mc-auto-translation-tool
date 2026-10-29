import type { Metadata } from "next";
import "./globals.css";

// The production domain is not recorded anywhere in this repository, so it is
// configured through `NEXT_PUBLIC_SITE_URL` (set it in the deploy environment;
// see website/README.md). It deliberately has no fallback origin: a `/`-prefixed
// asset URL discards the base path, so any repository-shaped fallback would emit
// `<fallback-host>/og-card.png` — an image that exists on no server.
function resolveMetadataBase(value: string | undefined): URL | undefined {
  if (!value) return undefined;
  try {
    return new URL(value);
  } catch {
    return undefined;
  }
}

const metadataBase = resolveMetadataBase(process.env.NEXT_PUBLIC_SITE_URL);

export const metadata: Metadata = {
  metadataBase,
  title: "MC 自动翻译工具",
  description: "完全公益、免费开源的 Minecraft Java 版全界面自动翻译模组。",
  icons: { icon: "/favicon.svg", shortcut: "/favicon.svg" },
  openGraph: {
    title: "MC 自动翻译工具",
    description: "让语言不再成为一起游戏的门槛。",
    type: "website",
    images: [{ url: "/og-card.png", width: 1200, height: 628, alt: "MC 自动翻译工具公益项目" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "MC 自动翻译工具",
    description: "让语言不再成为一起游戏的门槛。",
    images: ["/og-card.png"],
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
