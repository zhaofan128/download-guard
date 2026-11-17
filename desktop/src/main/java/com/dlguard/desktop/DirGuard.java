package com.dlguard.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 保存目录白名单。
 *
 * <p>本地服务即使只监听 127.0.0.1，请求里的 {@code dir} 也来自网络。
 * 只做 {@code Paths.get(dir)} 就等于把「往任意目录写文件」开放给能访问服务的任何进程。
 * 这里把允许写入的根目录收敛为：{@link Settings#saveDir} 与用户显式添加的
 * {@link Settings#allowedDirs}，并且在比较前把路径解析成真实路径（符号链接会展开），
 * 防止 {@code /tmp/ok/../../etc} 或「软链到系统目录」这类逃逸。
 *
 * <p>判定是纯函数，{@link SelfTest} 直接覆盖，不需要真的写盘。
 */
final class DirGuard {

    private DirGuard() { }

    /** 明确不允许作为保存目录的系统路径（精确匹配，不拦它们的子目录）。 */
    private static final Set<String> SYSTEM_DIRS = new LinkedHashSet<>(Arrays.asList(
            "/", "/bin", "/boot", "/dev", "/etc", "/lib", "/lib64", "/proc", "/root",
            "/sbin", "/sys", "/usr", "/var", "/System", "/Library", "/private",
            "c:\\", "c:\\windows", "c:\\program files", "c:\\program files (x86)"
    ));

    /**
     * 解析成「真实路径」：不存在的尾部保留，已存在的部分用 {@link Path#toRealPath()} 展开符号链接。
     * 这样即使目标目录还没创建，也不会被中间某层的软链带偏。
     */
    static Path canonical(Path raw) throws IOException {
        Path p = raw.toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<>();
        Path existing = p;
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name != null) missing.add(0, name);
            existing = existing.getParent();
        }
        if (existing == null) return p;
        Path real = existing.toRealPath();
        for (Path name : missing) real = real.resolve(name);
        return real.normalize();
    }

    static Path canonical(String raw) throws IOException {
        try {
            return canonical(Paths.get(raw));
        } catch (InvalidPathException e) {
            throw new IOException("路径不合法：" + raw, e);
        }
    }

    static boolean isUnder(Path child, Path root) {
        return child.equals(root) || child.startsWith(root);
    }

    static boolean isSystemDir(Path canonical) {
        String s = canonical.toString();
        if (s.isEmpty()) return true;
        String key = s.toLowerCase(Locale.ROOT);
        if (SYSTEM_DIRS.contains(key)) return true;
        // 盘符根目录：C:\、D:\ 之类
        return canonical.getParent() == null;
    }

    /**
     * 校验一个请求里的保存目录。
     *
     * @param raw        请求里的 dir（空表示默认目录）
     * @param roots      允许的根目录（已 canonical）
     * @param fallback   默认目录（已 canonical）
     * @return 可安全创建/写入的 canonical 路径
     * @throws IOException 路径非法、是系统目录、或不在允许根目录下
     */
    static Path resolveWritable(String raw, List<Path> roots, Path fallback) throws IOException {
        Path p;
        if (raw == null || raw.trim().isEmpty()) {
            p = fallback;
        } else {
            String text = raw.trim();
            if (text.contains("..")) throw new IOException("保存目录不能包含 ..");
            p = canonical(text);
        }
        if (isSystemDir(p)) throw new IOException("不允许写入系统目录或磁盘根目录：" + p);
        for (Path root : roots) {
            if (isUnder(p, root)) return p;
        }
        throw new IOException("保存目录不在允许范围内：" + p + "（可在设置里把目录加入允许列表）");
    }

    /** 用户显式新增保存目录时的校验：不能是系统目录，也不要求已经在白名单里。 */
    static Path resolveNewSaveDir(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty()) throw new IOException("目录不能为空");
        String text = raw.trim();
        if (text.contains("..")) throw new IOException("保存目录不能包含 ..");
        Path p = canonical(text);
        if (isSystemDir(p)) throw new IOException("不允许使用系统目录或磁盘根目录：" + p);
        return p;
    }

    /**
     * 从设置里收集允许根目录。无效项直接跳过，不影响已有任务继续下载。
     */
    static List<Path> allowedRoots(Settings settings) {
        List<Path> roots = new ArrayList<>();
        if (settings == null) return roots;
        addRoot(roots, settings.saveDir);
        if (settings.allowedDirs != null) {
            for (String d : settings.allowedDirs) addRoot(roots, d);
        }
        return roots;
    }

    private static void addRoot(List<Path> roots, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            Path p = canonical(raw);
            if (!isSystemDir(p) && !roots.contains(p)) roots.add(p);
        } catch (Exception ignored) { }
    }
}
