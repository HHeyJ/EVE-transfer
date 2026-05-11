package com.example.evetransfer.log;

import com.example.evetransfer.model.LogFileState;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 增量读取日志文件。
 *
 * 思路类似前端读取一个不断追加内容的日志文件：
 * 第一次从头读到尾，记住 "读到了第 N 个字节"；
 * 下次只读 N 字节之后的新内容。
 *
 * 这里用 Java 的 RandomAccessFile（随机访问文件），
 * 可以像操作数组一样跳到指定字节位置开始读。
 */
public class LogReader {

    // UTF-16LE 文件头两个字节是 0xFF 0xFE（BOM，Byte Order Mark）
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};

    /**
     * 读取文件中上次位置之后的新增内容。
     *
     * @param path  文件路径
     * @param state 该文件的读取状态（上次读到哪里、什么编码）
     * @return 新增行的列表
     */
    public List<String> readNewLines(Path path, LogFileState state) throws IOException {
        List<String> lines = new ArrayList<>();

        // "r" = 只读模式，不会锁文件（EVE 客户端正在写日志，我们不能锁它）
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long fileLength = raf.length(); // 文件当前总字节数
            if (fileLength <= state.getLastReadPosition()) {
                return lines; // 没有新内容
            }

            long startPos = state.getLastReadPosition();
            raf.seek(startPos); // 跳到上次读到的位置

            // 读取新增的字节到内存
            int bytesToRead = (int) (fileLength - startPos);
            byte[] buffer = new byte[bytesToRead];
            raf.readFully(buffer);
            state.setLastReadPosition(fileLength); // 更新进度

            // 检测编码：如果从文件开头读，且前两个字节是 BOM，说明是 UTF-16LE
            boolean hasBom = startPos == 0 && buffer.length >= 2
                    && buffer[0] == UTF16LE_BOM[0] && buffer[1] == UTF16LE_BOM[1];
            Charset charset = hasBom ? StandardCharsets.UTF_16LE
                    : (state.getCharset() != null ? state.getCharset() : StandardCharsets.UTF_8);
            state.setCharset(charset);

            // 把字节数组按编码转成字符串。如果有 BOM，跳过前两个字节
            int textOffset = hasBom ? 2 : 0;
            String text = new String(buffer, textOffset, buffer.length - textOffset, charset);

            // 如果是第一次读取（startPos == 0），需要解析文件头，跳过元数据
            if (startPos == 0) {
                lines = extractBodyLines(text, state);
            } else {
                // 增量读取：直接按行分割
                String[] rawLines = text.split("\r?\n");
                for (String line : rawLines) {
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
            }
        }

        return lines;
    }

    /**
     * 从完整文件内容中提取正文消息行，同时解析文件头里的 Channel Name。
     *
     * EVE 日志文件结构：
     * 1. 文件头元数据（Channel ID, Channel Name, Listener, Session started）
     * 2. 用 "---------------" 分隔
     * 3. 正文：每行以 "[ yyyy.MM.dd HH:mm:ss ]" 开头
     *
     * 我们只保留第 3 部分的行，同时从第 1 部分提取 Channel Name。
     */
    private List<String> extractBodyLines(String text, LogFileState state) {
        List<String> result = new ArrayList<>();
        String[] rawLines = text.split("\r?\n");
        boolean inBody = false;

        for (String rawLine : rawLines) {
            // 去掉首尾空白，同时去掉可能残留的 BOM 字符（\uFEFF）
            String line = rawLine.strip().replace("\uFEFF", "");
            if (line.isEmpty()) {
                continue;
            }

            // 从文件头提取频道名
            if (line.startsWith("Channel Name:")) {
                String channel = line.substring("Channel Name:".length()).trim();
                state.setChannelName(channel);
                continue;
            }

            // 检测正文开始：以 "[" 开头且包含时间格式的行
            // 例如：[ 2026.05.10 09:32:07 ]
            if (!inBody && line.startsWith("[")) {
                inBody = true;
            }

            if (inBody) {
                result.add(line);
            }
        }

        return result;
    }
}
