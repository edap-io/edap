package io.edap.container;

public enum Slot {
    PREVIOUS,   // 上一个 current 的"快速回滚"备份
    CURRENT,    // 当前接流量的版本
    STAGING     // 已启动但未接流量的版本（灰度/预发）
}
