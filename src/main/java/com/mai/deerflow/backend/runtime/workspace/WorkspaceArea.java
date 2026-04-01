package com.mai.deerflow.backend.runtime.workspace;

/**
 * 线程工作区中的固定根目录类型。
 */
public enum WorkspaceArea {
    /** agent 日常读写和命令执行的默认工作目录。 */
    WORKSPACE("workspace"),
    /** 用户上传文件的存放目录。 */
    UPLOADS("uploads"),
    /** agent 产物输出目录。 */
    OUTPUTS("outputs");

    private final String directoryName;

    WorkspaceArea(String directoryName) {
        this.directoryName = directoryName;
    }

    /**
     * 返回该区域在文件系统中的目录名。
     */
    public String directoryName() {
        return directoryName;
    }
}
