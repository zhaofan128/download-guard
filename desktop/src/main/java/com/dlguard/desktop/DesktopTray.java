package com.dlguard.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * 托盘图标。
 *
 * exe 用 GUI 头启动时没有控制台窗口，用户关掉浏览器就找不到程序了，
 * 也没有地方按 Ctrl+C。托盘给两个入口：打开界面、退出（退出前会保存断点）。
 * 没有桌面环境（服务器、SSH）时静默跳过，不影响命令行用法。
 */
public final class DesktopTray {

    private static TrayIcon icon;

    private DesktopTray() { }

    public static void install(final Engine engine, final Server server, final String url) {
        try {
            if (GraphicsEnvironment.isHeadless()) return;
            if (!SystemTray.isSupported()) return;

            Image image = loadIcon();
            if (image == null) return;

            PopupMenu menu = new PopupMenu();

            MenuItem open = new MenuItem("打开下载界面");
            open.addActionListener(e -> Util.browse(url));
            menu.add(open);

            menu.addSeparator();

            MenuItem quit = new MenuItem("退出（保存断点）");
            quit.addActionListener(e -> {
                try {
                    engine.shutdown();
                    server.stop();
                } catch (Exception ignored) { }
                SystemTray.getSystemTray().remove(icon);
                System.exit(0);
            });
            menu.add(quit);

            TrayIcon ti = new TrayIcon(image, "下载守护 " + Engine.VERSION, menu);
            ti.setImageAutoSize(true);
            ti.addActionListener(e -> Util.browse(url));
            SystemTray.getSystemTray().add(ti);
            icon = ti;
        } catch (Throwable ignored) {
            // 没有托盘的环境（部分 Linux 桌面、无 X 的容器、DISPLAY 指向不存在的服务）
            // 直接跳过。注意这里必须抓 Throwable：AWT 初始化失败抛的是 AWTError（Error），
            // 只抓 RuntimeException 会让整个程序起不来。
        }
    }

    private static Image loadIcon() {
        try (InputStream in = DesktopTray.class.getResourceAsStream("/web/icon-192.png")) {
            if (in == null) return null;
            return ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }
}
