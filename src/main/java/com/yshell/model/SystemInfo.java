package com.yshell.model;

public class SystemInfo {
    public String distro;
    public String kernel;
    public String uptime;
    public String systemTime;
    public String cpuModel;
    public String cpuCores;
    public double cpuPercent = -1;
    public double memPercent = -1;
    public String memValue;
    public double swapPercent = -1;
    public String swapValue;
    public int processRunning = -1;
    public int processSleeping = -1;
    public int processTotal = -1;
    public String[] networkInterfaces;
    public String uploadSpeed;
    public String downloadSpeed;
    public String latency;
    public ProcessInfo[] topProcesses;
    public DiskInfo[] diskInfo;
    public UserInfo[] allUsers;
}