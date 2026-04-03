package com.steve.mytvbroadcast.ui.focus

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Android TV Focus System - 提供统一的聚焦动画效果
 *
 * 根据 Android TV Design Guide 实现:
 * - 缩放指示: 聚焦时放大元素 (1.05x，符合 1.025x-1.1x 指南范围)
 * - 发光指示: 添加阴影高度效果
 * - 轮廓指示: 通过 XML selector 实现
 * - 颜色指示: 背景和内容颜色变化
 *
 * 组件状态矩阵:
 * - 默认/聚焦/按下 × 已启用/已停用/已选中
 */
object FocusEffects {

    // 缩放比例 - 符合 TV 设计指南 1.025x-1.1x 范围
    private const val SCALE_NORMAL = 1.0f
    private const val SCALE_FOCUSED = 1.05f

    // 阴影高度 - 发光指示
    private const val ELEVATION_FOCUSED = 8f
    private const val ELEVATION_PRESSED = 4f

    // 动画时长 - 指南建议 150-200ms
    private const val ANIMATION_DURATION_FOCUS = 150L
    private const val ANIMATION_DURATION_PRESSED = 100L

    // 按压时缩放 (轻微缩小)
    private const val SCALE_PRESSED = 0.98f

    /**
     * 为 View 启用标准聚焦动画效果
     * 组合使用: 缩放 + 阴影发光
     */
    fun enableFocusEffect(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        view.setOnFocusChangeListener { _, hasFocus ->
            animateFocus(view, hasFocus)
        }

        view.setOnClickListener {
            animatePressed(view)
        }
    }

    /**
     * 仅为 View 启用缩放聚焦效果（无阴影）
     */
    fun enableScaleFocusEffect(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        view.setOnFocusChangeListener { _, hasFocus ->
            animateScale(view, hasFocus)
        }
    }

    /**
     * 聚焦动画 - 缩放 + 阴影效果
     */
    fun animateFocus(view: View, focused: Boolean) {
        val scale = if (focused) SCALE_FOCUSED else SCALE_NORMAL
        val elevation = if (focused) ELEVATION_FOCUSED else 0f

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", scale).apply {
                    duration = ANIMATION_DURATION_FOCUS
                    interpolator = if (focused) OvershootInterpolator(1.2f) else DecelerateInterpolator()
                },
                ObjectAnimator.ofFloat(view, "scaleY", scale).apply {
                    duration = ANIMATION_DURATION_FOCUS
                    interpolator = if (focused) OvershootInterpolator(1.2f) else DecelerateInterpolator()
                },
                ObjectAnimator.ofFloat(view, "elevation", elevation).apply {
                    duration = ANIMATION_DURATION_FOCUS
                }
            )
            start()
        }
    }

    /**
     * 按压动画 - 轻微缩小反馈
     */
    fun animatePressed(view: View) {
        view.animate()
            .scaleX(SCALE_PRESSED)
            .scaleY(SCALE_PRESSED)
            .setDuration(ANIMATION_DURATION_PRESSED)
            .withEndAction {
                view.animate()
                    .scaleX(SCALE_FOCUSED)
                    .scaleY(SCALE_FOCUSED)
                    .setDuration(ANIMATION_DURATION_PRESSED)
                    .start()
            }
            .start()
    }

    /**
     * 仅缩放动画
     */
    fun animateScale(view: View, focused: Boolean) {
        val scale = if (focused) SCALE_FOCUSED else SCALE_NORMAL

        ObjectAnimator.ofFloat(view, "scaleX", scale).apply {
            duration = ANIMATION_DURATION_FOCUS
            interpolator = if (focused) OvershootInterpolator(1.2f) else DecelerateInterpolator()
            start()
        }

        ObjectAnimator.ofFloat(view, "scaleY", scale).apply {
            duration = ANIMATION_DURATION_FOCUS
            interpolator = if (focused) OvershootInterpolator(1.2f) else DecelerateInterpolator()
            start()
        }
    }

    /**
     * 立即设置聚焦状态（无动画）
     */
    fun setFocusImmediate(view: View, focused: Boolean) {
        view.scaleX = if (focused) SCALE_FOCUSED else SCALE_NORMAL
        view.scaleY = if (focused) SCALE_FOCUSED else SCALE_NORMAL
        view.elevation = if (focused) ELEVATION_FOCUSED else 0f
    }
}
