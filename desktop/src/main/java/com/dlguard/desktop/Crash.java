package com.dlguard.desktop;

import java.awt.GraphicsEnvironment;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.swing.JOptionPane;

/**
 * 启动失败的兜底处理。
 *
 * Windows 上双击 jar，JVM 只用 javaw 起进程，控制台不落地，任何未捕获异常都只剩
 * 一句「A Java Exception has occurred.」——用户没法反馈，开发者也没法定位。
 * 这里把堆栈写进文件，再弹一个带路径的对话框，至少能拿到可复现的信息。
 */
final class Crash {

    private Crash() {}

    static void report(Throwable t, String[] args) {
        StringWriter buffer = new StringWriter();
        t.printStackTrace(new PrintWriter(buffer));
        String trace = buffer.toString();

        String summary = t.getClass().getSimpleName()
                + (t.getMessage() == null || t.getMessage().isEmpty() ? "" : "：" + t.getMessage());
        Path log = write(trace, args, summary);

        System.err.println();
        System.err.println("  启动失败：" + summary);
        System.err.println("  日志：" + (log == null ? "（写入失败）" : log.toString()));
        System.err.println();

        show(summary, log);
        System.exit(1);
    }

    private static Path write(String trace, String[] args, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("下载守护 桌面版 启动失败\n");
        sb.append("时间   ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        sb.append("版本   ").append(Engine.VERSION).append('\n');
        sb.append("系统   ").append(System.getProperty("os.name", "?")).append(' ')
                .append(System.getProperty("os.version", "?")).append(" / ")
                .append(System.getProperty("os.arch", "?")).append('\n');
        sb.append("Java   ").append(System.getProperty("java.version", "?")).append(" (")
                .append(System.getProperty("java.vendor", "?")).append(") / ")
                .append(System.getProperty("java.home", "?")).append('\n');
        sb.append("参数   ");
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(args[i]);
            }
        }
        sb.append("\n\n异常   ").append(summary).append("\n\n").append(trace);

        String name = "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log";
        Path[] dirs = new Path[] {
                home(".dlguard-desktop"),
                Paths.get(System.getProperty("java.io.tmpdir", ".")),
                Paths.get(".")
        };
        for (Path dir : dirs) {
            try {
                if (dir == null) continue;
                Files.createDirectories(dir);
                Path file = dir.resolve(name);
                Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
                return file.toAbsolutePath();
            } catch (Throwable ignored) {
                // 换下一个候选目录
            }
        }
        return null;
    }

    private static Path home(String child) {
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) return null;
        return Paths.get(home, child);
    }

    private static void show(String summary, Path log) {
        try {
            if (GraphicsEnvironment.isHeadless()) return;
            // 系统默认字体在中文 Windows 上未必能显示中文，显式指定一次
            String text = "下载守护没能启动。\n\n"
                    + summary + "\n\n"
                    + (log == null
                        ? "日志写入失败，请在命令行执行 java -jar 查看完整错误。"
                        : "错误日志：\n" + log + "\n\n把这个文件发给开发者即可定位问题。");
            JOptionPane.showMessageDialog(null, text, "下载守护 启动失败", JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // 连对话框都起不来就算了，堆栈已经打到 stderr
        }
    }
}
