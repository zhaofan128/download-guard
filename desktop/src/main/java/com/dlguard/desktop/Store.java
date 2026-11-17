package com.dlguard.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地存储：配置、任务列表、下载中的临时文件。
 *
 * 全部放在用户目录下的 .dlguard-desktop，不碰系统注册表/XDG 配置目录——
 * 换机器时只要拷这个文件夹（或只拷 tasks.json）就能接上进度。
 */
public final class Store {

    public final Path baseDir;
    public final Path partsDir;
    public final Path quarantineDir;
    private final Path tasksFile;
    private final Path settingsFile;

    public Store() {
        this(Paths.get(System.getProperty("user.home", "."), ".dlguard-desktop"));
    }

    public Store(Path baseDir) {
        this.baseDir = baseDir;
        this.partsDir = baseDir.resolve("parts");
        this.quarantineDir = baseDir.resolve("quarantine");
        this.tasksFile = baseDir.resolve("tasks.json");
        this.settingsFile = baseDir.resolve("settings.json");
        try {
            Files.createDirectories(partsDir);
            Files.createDirectories(quarantineDir);
        } catch (IOException ignored) { }
    }

    public Path partPath(Task t) {
        return partsDir.resolve(t.id + ".part");
    }

    public Path metaPath(Task t) {
        return partsDir.resolve(t.id + ".meta.json");
    }

    public synchronized List<Task> loadTasks() {
        List<Task> list = new ArrayList<>();
        String text = Util.readText(tasksFile, "");
        if (text.isEmpty()) return list;
        try {
            Map<String, Object> root = Json.map(Json.parse(text));
            for (Object o : Json.list(root.get("tasks"))) {
                list.add(Task.fromMap(Json.map(o)));
            }
        } catch (Exception e) {
            // 文件损坏不让程序起不来：备份一份，从空列表开始
            try {
                Files.move(tasksFile, tasksFile.resolveSibling("tasks.corrupt-" + System.currentTimeMillis() + ".json"));
            } catch (IOException ignored) { }
        }
        return list;
    }

    public synchronized void saveTasks(List<Task> tasks) {
        List<Object> arr = new ArrayList<>();
        int start = Math.max(0, tasks.size() - 300);
        for (int i = start; i < tasks.size(); i++) arr.add(tasks.get(i).toMap());
        String json = Json.write(Json.obj("version", 1, "tasks", arr));
        try {
            Util.atomicWrite(tasksFile, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    public synchronized Settings loadSettings() {
        String text = Util.readText(settingsFile, "");
        if (text.isEmpty()) return new Settings();
        try {
            return Settings.fromMap(Json.map(Json.parse(text)));
        } catch (Exception e) {
            return new Settings();
        }
    }

    public synchronized void saveSettings(Settings s) {
        try {
            Util.atomicWrite(settingsFile, Json.write(s.toMap()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
