package com.openclaw.android.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.openclaw.android.MainActivity
import com.openclaw.android.R
import com.openclaw.android.TerminalSessionManager
import com.openclaw.android.databinding.ActivityMainBinding
import java.util.*

/**
 * Gestiona la barra de pestañas de sesiones de terminal.
 *
 * Responsabilidad única: crear, actualizar y eliminar las pestañas
 * que representan sesiones de terminal activas.
 */
internal class TerminalTabManager(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val sessionManager: TerminalSessionManager,
) {

    /**
     * Actualiza toda la barra de pestañas según las sesiones actuales.
     */
    fun updateSessionTabs() {
        val tabsLayout = binding.tabsLayout
        tabsLayout.removeAllViews()
        val sessions = sessionManager.getSessionsInfo()
        for (info in sessions) {
            val tabWrapper = createSessionTab(info)
            tabsLayout.addView(tabWrapper)
            if (info["active"] as Boolean) {
                binding.sessionTabBar.post {
                    binding.sessionTabBar.smoothScrollTo(tabWrapper.left, 0)
                }
            }
        }
        tabsLayout.addView(createAddButton())
    }

    /**
     * Cierra una sesión desde su pestaña.
     */
    fun closeSessionFromTab(handleId: String) {
        if (sessionManager.sessionCount <= 1) sessionManager.createSession()
        sessionManager.closeSession(handleId)
        binding.terminalView.requestFocus()
    }

    // ── Construcción de pestañas ───────────────────────────────────────────

    private fun createSessionTab(info: Map<String, Any>): LinearLayout {
        val id = info["id"] as String
        val name = info["name"] as String
        val active = info["active"] as Boolean
        val finished = info["finished"] as Boolean

        val tabWrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.tab_margin) }
            setBackgroundColor(
                ContextCompat.getColor(
                    activity,
                    if (active) R.color.tabActiveBackground else R.color.tabInactiveBackground,
                )
            )
            isFocusable = false
            isFocusableInTouchMode = false
        }

        tabWrapper.addView(createTabContent(name, active, finished, id))
        tabWrapper.addView(createTabIndicator(active))
        tabWrapper.setOnClickListener {
            sessionManager.switchSession(id)
            binding.terminalView.requestFocus()
        }
        return tabWrapper
    }

    private fun createTabContent(
        name: String,
        active: Boolean,
        finished: Boolean,
        id: String,
    ): LinearLayout {
        val hPad = activity.resources.getDimensionPixelSize(R.dimen.tab_padding_h)
        val vPad = activity.resources.getDimensionPixelSize(R.dimen.tab_padding_v)
        val closePad = activity.resources.getDimensionPixelSize(R.dimen.tab_close_size) / 4

        val tabContent = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hPad, vPad, closePad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f,
            )
            isFocusable = false
            isFocusableInTouchMode = false
        }

        val nameView = TextView(activity).apply {
            text = name
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                activity.resources.getDimension(R.dimen.tab_name_text_size)
            )
            val textColor = when {
                finished -> R.color.tabTextFinished
                active -> R.color.tabTextPrimary
                else -> R.color.tabTextSecondary
            }
            setTextColor(ContextCompat.getColor(activity, textColor))
            if (finished) setTypeface(typeface, Typeface.ITALIC)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val closeView = TextView(activity).apply {
            text = activity.getString(R.string.close_session)
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                activity.resources.getDimension(R.dimen.tab_close_text_size)
            )
            setTextColor(ContextCompat.getColor(activity, R.color.tabTextSecondary))
            setPadding(closePad, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { closeSessionFromTab(id) }
        }

        tabContent.addView(nameView)
        tabContent.addView(closeView)
        return tabContent
    }

    private fun createTabIndicator(active: Boolean): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            activity.resources.getDimensionPixelSize(R.dimen.tab_indicator_height),
        )
        setBackgroundColor(
            ContextCompat.getColor(
                activity,
                if (active) R.color.tabAccent else android.R.color.transparent,
            )
        )
    }

    private fun createAddButton(): TextView = TextView(activity).apply {
        text = activity.getString(R.string.add_session)
        setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            activity.resources.getDimension(R.dimen.tab_add_text_size)
        )
        setTextColor(ContextCompat.getColor(activity, R.color.tabAddButton))
        val pad = activity.resources.getDimensionPixelSize(R.dimen.tab_add_padding)
        setPadding(pad, 0, pad, 0)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
        isFocusable = false
        isFocusableInTouchMode = false
        setOnClickListener {
            sessionManager.createSession()
            binding.terminalView.requestFocus()
        }
    }
}
