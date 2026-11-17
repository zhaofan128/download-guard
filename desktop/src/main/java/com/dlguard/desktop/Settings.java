package com.dlguard.desktop;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** 桌面版设置项。改完立即生效，不需要重启。 */
public final class Settings {

    public String saveDir = defaultSaveDir();
    public int maxConcurrent = 3;
    public int connections = 8;
    public long speedLimitKB = 0;        // 0 = 不限速
    public String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36 DownloadGuard/3.0";
    public String referer = "";
    public String proxy = "";           // host:port，留空不走代理
    public String theme = "auto";       // auto | light | dark
    public int port = 8765;
    public boolean lan = false;         // 允许局域网其他设备访问（手机连电脑）
    public boolean autoResume = true;   // 启动时自动续传未完成任务
    public boolean verifyApk = true;    // 下载完 APK/ZIP 做结构与签名检查
    public boolean quarantineOnMismatch = true;
    public boolean hlsRemux = true;     // HLS 下完尝试用 ffmpeg 合成 mp4
    public String aria2Path = "";       // 留空则自动在 PATH 里找

    public static String defaultSaveDir() {
        String home = System.getProperty("user.home", ".");
        Path downloads = Paths.get(home, "Downloads");
        return downloads.toAbsolutePath().toString();
    }

    public Map<String, Object> toMap() {
        return Json.obj(
                "saveDir", saveDir,
                "maxConcurrent", maxConcurrent,
                "connections", connections,
                "speedLimitKB", speedLimitKB,
                "userAgent", userAgent,
                "referer", referer,
                "proxy", proxy,
                "theme", theme,
                "port", port,
                "lan", lan,
                "autoResume", autoResume,
                "verifyApk", verifyApk,
                "quarantineOnMismatch", quarantineOnMismatch,
                "hlsRemux", hlsRemux,
                "aria2Path", aria2Path
        );
    }

    public static Settings fromMap(Map<String, Object> m) {
        Settings s = new Settings();
        s.saveDir = Json.str(m, "saveDir", s.saveDir);
        s.maxConcurrent = clamp(Json.i(m, "maxConcurrent", s.maxConcurrent), 1, 10);
        s.connections = clamp(Json.i(m, "connections", s.connections), 1, 32);
        s.speedLimitKB = Math.max(0, Json.num(m, "speedLimitKB", s.speedLimitKB));
        s.userAgent = Json.str(m, "userAgent", s.userAgent);
        s.referer = Json.str(m, "referer", s.referer);
        s.proxy = Json.str(m, "proxy", s.proxy);
        s.theme = Json.str(m, "theme", s.theme);
        s.port = clamp(Json.i(m, "port", s.port), 1024, 65535);
        s.lan = Json.bool(m, "lan", s.lan);
        s.autoResume = Json.bool(m, "autoResume", s.autoResume);
        s.verifyApk = Json.bool(m, "verifyApk", s.verifyApk);
        s.quarantineOnMismatch = Json.bool(m, "quarantineOnMismatch", s.quarantineOnMismatch);
        s.hlsRemux = Json.bool(m, "hlsRemux", s.hlsRemux);
        s.aria2Path = Json.str(m, "aria2Path", s.aria2Path);
        return s;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
