package com.example.evetransfer.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 跟踪单个日志文件的读取进度。
 * 相当于前端读取大文件时的 "offset 记录器"，
 * 记录：读到哪个字节了、文件是什么编码、频道名是什么。
 */
public class LogFileState {

    private final Path path;           // 文件路径
    private Charset charset = StandardCharsets.UTF_8; // 文件编码，默认 UTF-8
    private long lastReadPosition = 0; // 上次读到的字节位置，下次从这里继续
    private String channelName;        // 从第一行解析出的频道名，用于缓存

    public LogFileState(Path path) {
        this.path = path;
    }

    public Path getPath() { return path; }
    public Charset getCharset() { return charset; }
    public void setCharset(Charset charset) { this.charset = charset; }
    public long getLastReadPosition() { return lastReadPosition; }
    public void setLastReadPosition(long pos) { this.lastReadPosition = pos; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String name) { this.channelName = name; }
}
