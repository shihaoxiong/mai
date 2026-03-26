package com.mai.deerflow.backend.runtime.workspace;

public enum WorkspaceArea {
    WORKSPACE("workspace"),
    UPLOADS("uploads"),
    OUTPUTS("outputs");

    private final String directoryName;

    WorkspaceArea(String directoryName) {
        this.directoryName = directoryName;
    }

    public String directoryName() {
        return directoryName;
    }
}
