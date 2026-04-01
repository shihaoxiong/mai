package com.mai.deerflow.backend.runtime.contract;

/**
 * 线程对外暴露的轻量消息视图。
 *
 * 当前只返回前端渲染最需要的角色与文本内容，
 * 避免直接泄露底层框架消息对象结构。
 */
public record ThreadMessage(
        String role,
        String content
) {
}
