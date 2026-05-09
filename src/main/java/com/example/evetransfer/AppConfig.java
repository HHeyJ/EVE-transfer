package com.example.evetransfer;

/**
 * 全局配置常量。相当于前端项目里的 config.js / .env 文件。
 * 所有不可变的数值都放在这里，方便以后统一修改。
 */
public final class AppConfig {

    // 私有构造方法，防止被实例化（工具类惯用写法）
    private AppConfig() {}

    // 窗口默认透明度（0 = 全透明，1 = 不透明）
    public static final double DEFAULT_OPACITY = 0.85;
    // 透明度滑块的最小值
    public static final double MIN_OPACITY = 0.2;
    // 透明度滑块的最大值
    public static final double MAX_OPACITY = 1.0;

    // 文件轮询间隔（毫秒）。macOS 的 WatchService 很慢，
    // 所以我们自己每 500 毫秒扫一遍目录做兜底。
    public static final long POLL_INTERVAL_MS = 500;

    // 翻译队列最大长度，防止大模型接口被冲垮
    public static final int MAX_TRANSLATION_QUEUE = 50000;

    // 目标翻译语言，zh = 中文
    public static final String TARGET_LANGUAGE = "zh";

    /**
     * 根据操作系统返回 EVE 默认日志路径。
     * 相当于 web 里的 navigator.platform 判断。
     */
    public static String getDefaultLogDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            return home + "\\Documents\\EVE\\logs\\Chatlogs";
        } else if (os.contains("mac")) {
            return home + "/Documents/EVE/logs/Chatlogs";
        } else {
            return home + "/Documents/EVE/logs/Chatlogs";
        }
    }
}
