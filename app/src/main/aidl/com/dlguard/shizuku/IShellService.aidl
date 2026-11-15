package com.dlguard.shizuku;

/**
 * Shizuku 用户服务接口：跑在 shell(uid=2000) 进程里的特权执行器。
 * 协议见 ShellService.exec 的返回格式（exitCode + 分隔符 + stdout + stderr）。
 */
interface IShellService {
    String exec(String command);
    void exit();
}
