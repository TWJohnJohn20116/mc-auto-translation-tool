import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "MC 自动翻译工具",
  description: "完全公益、免费开源的 Minecraft Java 版全界面自动翻译模组。",
  icons: { icon: "/favicon.svg", shortcut: "/favicon.svg" },
  openGraph: {
    title: "MC 自动翻译工具",
    description: "让语言不再成为一起游戏的门槛。",
    type: "website",
    images: [{ url: "/og-card.png", width: 1733, height: 907, alt: "MC 自动翻译工具公益项目" }],
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
