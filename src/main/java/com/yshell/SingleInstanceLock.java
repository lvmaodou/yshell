package com.yshell;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

public class SingleInstanceLock {

    private static final Logger LOGGER = LoggerFactory.getLogger(SingleInstanceLock.class);
    private static final String MUTEX_NAME = "Global\\YShell_SingleInstance";
    private static final String LOCK_FILE = ".instance-lock";

    /**
     * 尝试获取单实例锁。如果已有实例在运行,返回 false。
     */
    public static boolean tryLock() {
        if (isWindows()) {
            return tryWindowsMutex();
        }
        return tryFileLock();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean tryWindowsMutex() {
        try {
            Kernel32.INSTANCE.CreateMutex(null, false, MUTEX_NAME);
            return Kernel32.INSTANCE.GetLastError() != WinError.ERROR_ALREADY_EXISTS;
        } catch (Exception e) {
            LOGGER.warn("创建 Windows 互斥体失败,允许多实例运行", e);
            return true;
        }
    }

    private static boolean tryFileLock() {
        File lock = new File(System.getProperty("user.home"), ".yshell" + File.separator + LOCK_FILE);
        if (!lock.getParentFile().exists()) {
            boolean sus = lock.getParentFile().mkdirs();
            if (!sus){
                return true;
            }
        }
        try( RandomAccessFile lockFile = new RandomAccessFile(lock, "rw")) {
            FileChannel channel = lockFile.getChannel();
            FileLock fileLock = channel.tryLock();
            return fileLock != null;
        } catch (IOException e) {
            LOGGER.warn("获取文件锁失败,允许多实例运行", e);
            return true;
        }
    }
}
