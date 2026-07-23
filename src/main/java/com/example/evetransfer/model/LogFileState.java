package com.example.evetransfer.model;

import lombok.Getter;
import lombok.Setter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 跟踪单个日志文件的读取进度。
 * 相当于前端读取大文件时的 "offset 记录器"，
 * 记录：读到哪个字节了、文件是什么编码、频道名是什么。
 */
@Getter
public class LogFileState {

    private final Path path;

    @Setter
    private Charset charset = StandardCharsets.UTF_8;

    @Setter
    private long lastReadPosition = 0;

    @Setter
    private String channelName;

    public LogFileState(Path path) {
        this.path = path;
    }
}
