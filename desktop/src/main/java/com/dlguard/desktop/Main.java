package com.dlguard.desktop;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面版入口。
 *
 * 用「本地服务 + 浏览器界面」而不是 Swing/JavaFX：
 * - 一套 UI 同时适配 Windows / macOS / Linux，还能直接用手机浏览器打开（--lan）；
 * - 界面用系统自带浏览器渲染，字体、缩放、触控滚动全部跟随系统；
 * - 升级只换一个 jar，不用重新装客户端。
 */
public final class Main {

    public static void main(String[] args) {
        int port = 0;
        boolean lan = false;
        boolean openBrowser = true;
        String dir = null;
        String home = null;
        List<String> addUrls = new ArrayList<>();
        List<String> allowHosts = new ArrayList<>();
        boolean statusOnly = false;
        boolean wait = false;
        boolean quiet = false;
        String sha = "";
        int cliConn = 0;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--port":
                    if (i + 1 < args.length) port = parseInt(args[++i], 0);
                    break;
                case "--lan":
                    lan = true;
                    break;
                case "--no-browser":
                case "--headless":
                    openBrowser = false;
                    break;
                case "--dir":
                    if (i + 1 < args.length) dir = args[++i];
                    break;
                case "--home":
                    if (i + 1 < args.length) home = args[++i];
                    break;
                case "--add":
                    if (i + 1 < args.length) addUrls.add(args[++i]);
                    break;
                case "--status":
                    statusOnly = true;
                    break;
                case "--wait":
                    wait = true;
                    break;
                case "--quiet":
                    quiet = true;
                    break;
                case "--sha256":
                    if (i + 1 < args.length) sha = args[++i];
                    break;
                case "--connections":
                    if (i + 1 < args.length) cliConn = parseInt(args[++i], 0);
                    break;
                case "--allow-host":
                    if (i + 1 < args.length) {
                        for (String h : args[++i].split(",")) {
                            if (!h.trim().isEmpty()) allowHosts.add(h.trim());
                        }
                    }
                    break;
                case "--version":
                    System.out.println("DownloadGuard Desktop " + Engine.VERSION);
                    return;
                case "--selftest":
                    System.exit(SelfTest.run());
                    return;
                case "--help":
                case "-h":
                    printHelp();
                    return;
                default:
                    if (a.startsWith("--port=")) port = parseInt(a.substring(7), 0);
                    else if (a.startsWith("--dir=")) dir = a.substring(6);
                    else if (a.startsWith("--home=")) home = a.substring(7);
                    else if (a.startsWith("--add=")) addUrls.add(a.substring(6));
                    else if (a.startsWith("--sha256=")) sha = a.substring(9);
                    else if (a.startsWith("--connections=")) cliConn = parseInt(a.substring(14), 0);
                    else if (a.startsWith("--allow-host=")) {
                        for (String h : a.substring(13).split(",")) {
                            if (!h.trim().isEmpty()) allowHosts.add(h.trim());
                        }
                    }
                    break;
            }
        }

        Store store = (home == null || home.trim().isEmpty())
                ? new Store()
                : new Store(Paths.get(home.trim()));

        // 命令行模式：只和正在运行的实例对话，自己不起服务、不碰实例锁
        if (statusOnly || !addUrls.isEmpty()) {
            int code = statusOnly
                    ? Cli.status(store)
                    : Cli.add(store, addUrls, dir, cliConn, sha, wait, quiet);
            System.exit(code);
        }

        if (!store.lockInstance()) {
            int running = store.readInstancePort();
            String runningToken = store.readInstanceToken();
            System.out.println();
            System.out.println("  已经有一个 DownloadGuard 在运行（数据目录 " + store.baseDir + "）。");
            if (running > 0) {
                String url = "http://127.0.0.1:" + running + "/"
                        + (runningToken.isEmpty() ? "" : "?token=" + runningToken);
                System.out.println("  直接打开：" + url);
                if (openBrowser) Util.browse(url);
            }
            System.out.println("  要开第二个实例，请加 --home 指定不同的数据目录。");
            return;
        }
        Engine engine = new Engine(store);
        if (dir != null && !dir.trim().isEmpty()) {
            try {
                engine.settings.saveDir = DirGuard.canonical(dir.trim()).toString();
                store.saveSettings(engine.settings);
            } catch (Exception e) {
                System.err.println("保存目录不可用：" + e.getMessage());
                return;
            }
        }
        if (port <= 0) port = engine.settings.port;

        Server server;
        try {
            server = new Server(engine, port, lan, allowHosts);
        } catch (Exception e) {
            System.err.println("启动失败：无法监听端口 " + port + "（" + e.getMessage() + "）");
            System.err.println("可以换一个端口：java -jar DownloadGuard-Desktop.jar --port 9000");
            return;
        }
        engine.serverPort = server.port();
        store.writeInstanceInfo(server.port(), server.token());
        engine.start();
        server.start();

        String os = System.getProperty("os.name", "");
        System.out.println();
        System.out.println("  DownloadGuard Desktop " + Engine.VERSION);
        System.out.println("  ---------------------------------------------");
        System.out.println("  本机访问   " + server.cleanUrl() + "（首次请用下面的完整链接）");
        System.out.println("  完整链接   " + server.localUrl());
        if (lan) {
            System.out.println("  手机/平板  " + server.lanUrl());
            System.out.println("             （同一 WiFi 下用手机浏览器打开，令牌已带在链接里）");
        }
        System.out.println("  保存目录   " + engine.settings.saveDir);
        System.out.println("  数据目录   " + store.baseDir);
        System.out.println("  运行环境   " + os + " / Java " + System.getProperty("java.version"));
        System.out.println("  退出       Ctrl + C");
        System.out.println();

        if (openBrowser) Util.browse(server.localUrl());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                engine.shutdown();
                server.stop();
            } catch (Exception ignored) { }
            System.out.println("已保存进度，退出。");
        }, "shutdown"));
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static void printHelp() {
        List<String> lines = new ArrayList<>();
        lines.add("DownloadGuard Desktop " + Engine.VERSION);
        lines.add("");
        lines.add("用法：java -jar DownloadGuard-Desktop.jar [选项]");
        lines.add("");
        lines.add("  --port <端口>     指定端口，默认 8765（被占用会自动顺延）");
        lines.add("  --lan             允许同一局域网的手机/平板访问（带访问令牌）");
        lines.add("  --no-browser      启动后不自动打开浏览器");
        lines.add("  --dir <目录>      默认保存目录");
        lines.add("  --home <目录>     数据目录（任务与断点），默认 ~/.dlguard-desktop");
        lines.add("  --allow-host <名> 额外允许的 Host（逗号分隔），仅在用自定义域名访问时需要");
        lines.add("  --selftest        跑内置自检（CI 用），失败返回非 0");
        lines.add("  --version         打印版本");
        lines.add("  --help            显示帮助");
        lines.add("");
        lines.add("命令行投递（配合正在运行的实例，脚本/CI 用）：");
        lines.add("  --add <链接>      投递一个下载任务，可重复");
        lines.add("  --status          打印当前队列");
        lines.add("  --wait            投递后等到任务结束，失败返回非 0");
        lines.add("  --sha256 <值>     给投递的任务带上校验基准");
        lines.add("  --connections <n> 投递任务的连接数（1~32）");
        lines.add("  --quiet           只输出结果，不刷进度");
        for (String l : lines) System.out.println(l);
    }
}
