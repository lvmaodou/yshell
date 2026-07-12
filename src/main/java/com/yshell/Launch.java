package com.yshell;

import javafx.application.Application;

public class Launch {
    public static void main(String[] args) {
        if (!SingleInstanceLock.tryLock()) {
            System.exit(0);
        }
        Application.launch(MainApplication.class, args);
    }
}
