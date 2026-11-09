package com.steve.mytvbroadcast.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 首页频道流：顶部 padding 是 hero 的位置，焦点移动不许把流拽回去对齐那段 padding。 */
class FlowRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** 纵向移动全部交给显式跳行，横向换卡时整条流必须原地不动。 */
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean = false
}

/** 同上，但拦的是真正生效的那条路：requestChildFocus 会走 LayoutManager 按 padding 框对齐焦点。 */
class FlowLinearLayoutManager(context: Context) : LinearLayoutManager(context, VERTICAL, false) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        focusedRect: Rect,
        immediate: Boolean
    ): Boolean = false

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        focusedRect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean = false
}
