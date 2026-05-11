package com.example.evetransfer.log;

import com.example.evetransfer.model.ChatMessage;
import com.example.evetransfer.model.LogFileState;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志行解析器。相当于前端用正则从字符串里提取字段。
 *
 * EVE 欧服真实日志一行长这样：
 * [ 2026.05.10 09:32:07 ] EVE系统 > 频道更换为本地：欧斯蒙*
 * [ 2026.05.10 09:33:02 ] Zhang Chaoya0 > 统合部的要吗
 *
 * 我们要拆成：时间、玩家、内容 三个字段。
 * 频道名不再从每行里提取，而是从文件头解析后传入（通过 LogFileState）。
 */
public class LogParser {

    // 日期格式：yyyy.MM.dd HH:mm:ss（注意是点分隔）
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");

    // 正则表达式：
    // ^\[\s*         匹配开头的 [ 和可能的空格
    // (\d{4}\.\d{2}\.\d{2}\s+\d{2}:\d{2}:\d{2})  捕获时间戳
    // \s*\]          匹配 ] 前的空格和 ]
    // \s*(.+?)\s*    捕获玩家名（非贪婪，直到遇到 >）
    // >\s*(.+)       匹配 > 和消息内容
    // $               行尾
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^\\[\\s*(\\d{4}\\.\\d{2}\\.\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s*\\]\\s*(.+?)\\s*>\\s*(.+)$"
    );

    /**
     * 解析单行日志。
     *
     * @param line  要解析的行文本
     * @param state 文件状态，从中获取频道名（Channel Name 从文件头解析）
     * @return Optional<ChatMessage>，解析失败时返回 empty（比如空行、格式不对的、置顶消息）
     */
    public Optional<ChatMessage> parseLine(String line, LogFileState state) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }

        // 去掉可能残留的 BOM 字符（\uFEFF），再做匹配
        String cleanLine = line.replace("\uFEFF", "").strip();
        Matcher matcher = LINE_PATTERN.matcher(cleanLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        try {
            LocalDateTime timestamp = LocalDateTime.parse(matcher.group(1), TIMESTAMP_FORMAT);
            String player = matcher.group(2).trim();
            String message = matcher.group(3).trim();

            // 忽略置顶消息：EVE系统 发送的 "频道置顶信息：" 开头的消息
            if ("EVE系统".equals(player) && message.startsWith("频道置顶信息：")) {
                return Optional.empty();
            }

            // 频道名从文件头获取，如果还没解析到，用文件名兜底（后面会修正）
            String channel = state.getChannelName() != null ? state.getChannelName() : "Unknown";

            return Optional.of(new ChatMessage(timestamp, channel, player, message));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
