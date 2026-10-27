package com.morris.mttpsl.ui

/** 下单面板的纯 UI 文案/规则（便于单元测试） */
object PanelUi {
    /** 切换按钮文案：展开时显示“收起 ▲”，收起时显示“展开 ▼”——两个方向都可点 */
    fun toggleLabel(expanded: Boolean) = if (expanded) "收起 ▲" else "展开 ▼"
}
