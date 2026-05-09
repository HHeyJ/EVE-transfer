package com.example.evetransfer.log;

import com.example.evetransfer.model.ChatMessage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志行解析器。相当于前端用正则从字符串里提取字段。
 *
 * EVE 日志一行长这样：
 * 2026-05-10 00:00:00 [Alliance] Nova Fox: locals spiked to 15
 *
 * 我们要拆成：时间、频道、玩家、内容 四个字段。
 */
public class LogParser {

    // 日期格式：yyyy-MM-dd HH:mm:ss
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 正则表达式，分组分别对应：时间、频道、玩家、消息内容
    // ^ 开头，$ 结尾，\[ \] 匹配方括号，[^:]+ 匹配非冒号字符（玩家名）
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) \\[(.+?)\\] ([^:]+): (.+)$"
    );

    /**
     * 解析单行日志。
     * 返回 Optional<ChatMessage>，解析失败时返回 empty（比如空行、格式不对的）。
     * 类比 JS：相当于 string.match(regex) 然后判断有没有结果。
     */
    public Optional<ChatMessage> parseLine(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = LINE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            LocalDateTime timestamp = LocalDateTime.parse(matcher.group(1), TIMESTAMP_FORMAT);
            String channel = matcher.group(2).trim();
            String player = matcher.group(3).trim();
            String message = matcher.group(4).trim();
            return Optional.of(new ChatMessage(timestamp, channel, player, message));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
