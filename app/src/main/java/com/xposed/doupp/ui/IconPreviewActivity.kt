package com.xposed.doupp.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xposed.doupp.util.IconRes

/**
 * 图标预览页（开发/映射用）
 *
 * 把模块自带的 dyxs_xx 图标逐个渲染出来，并显示其资源名，
 * 方便对照逗音小能手界面，把资源名映射到对应功能。
 *
 * 打开方式: 桌面「Dou++ 图标预览」图标，或 adb:
 *   am start -n com.xposed.doupp/.ui.IconPreviewActivity
 */
class IconPreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()

        for (i in 1..25) {
            val name = "dyxs_%02d".format(i)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            }
            val iv = ImageView(this).apply {
                val d: Drawable? = IconRes.getDrawable(this@IconPreviewActivity, name)
                setImageDrawable(d)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            val tv = TextView(this).apply {
                text = name
                textSize = 15f
                setPadding((16 * density).toInt(), 0, 0, 0)
            }
            row.addView(iv)
            row.addView(tv)
            root.addView(row)
        }

        scroll.addView(root)
        setContentView(scroll)
    }
}
