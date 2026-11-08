package com.qiubo.optimaltv.ui.components

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Imperative focus/keyboard bridge for an Android framework EditText hosted by Compose.
 *
 * The editor itself owns drawing, selection, cursor, IME and accessibility behaviour. This handle
 * only lets the TV navigation layer move window focus to it before it has been attached.
 */
@Stable
class SystemEditTextHandle {
    private var editor: EditText? = null
    private var pendingShowKeyboard: Boolean? = null

    fun requestFocus(showKeyboard: Boolean = true) {
        pendingShowKeyboard = showKeyboard
        val target = editor ?: return
        pendingShowKeyboard = null
        target.post { requestNativeFocus(target, showKeyboard) }
    }

    fun showKeyboard() {
        requestFocus(showKeyboard = true)
    }

    fun hideKeyboard() {
        pendingShowKeyboard = null
        val target = editor ?: return
        target.context
            .getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(target.windowToken, 0)
    }

    internal fun attach(view: EditText) {
        editor = view
        pendingShowKeyboard?.let { showKeyboard ->
            pendingShowKeyboard = null
            view.post { requestNativeFocus(view, showKeyboard) }
        }
    }

    internal fun detach(view: EditText) {
        if (editor === view) editor = null
    }
}

@Composable
fun rememberSystemEditTextHandle(): SystemEditTextHandle = remember { SystemEditTextHandle() }

/**
 * A framework EditText, not a Compose text field. No background, border, cursor or focus ring is
 * drawn by the app; Android's themed EditText provides all of them.
 */
@Composable
fun SystemEditText(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    handle: SystemEditTextHandle = rememberSystemEditTextHandle(),
    enabled: Boolean = true,
    password: Boolean = false,
    numeric: Boolean = false,
    imeAction: Int = EditorInfo.IME_ACTION_NEXT,
    onImeAction: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    onKeyDown: (KeyEvent) -> Boolean = { false },
    textSizeSp: Float = 20f,
    textColor: Color = Color.White,
    hintTextColor: Color = Color(0xB3FFFFFF),
    selectAllOnFocus: Boolean = false,
    autoFocus: Boolean = false,
    autoFocusShowsKeyboard: Boolean = false,
) {
    val currentOnValueChange = rememberUpdatedState(onValueChange)
    val currentOnImeAction = rememberUpdatedState(onImeAction)
    val currentOnFocusChanged = rememberUpdatedState(onFocusChanged)
    val currentOnKeyDown = rememberUpdatedState(onKeyDown)
    val currentImeAction = rememberUpdatedState(imeAction)
    val inputType =
        if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else if (numeric) {
            InputType.TYPE_CLASS_NUMBER
        } else {
            InputType.TYPE_CLASS_TEXT
        }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                backgroundTintList = null
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                isFocusable = true
                isFocusableInTouchMode = true
                isCursorVisible = true
                setSelectAllOnFocus(selectAllOnFocus)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            text: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) = Unit

                        override fun onTextChanged(
                            text: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) = Unit

                        override fun afterTextChanged(text: Editable?) {
                            currentOnValueChange.value(text?.toString().orEmpty())
                        }
                    },
                )
                setOnEditorActionListener { _, actionId, event ->
                    val action = currentImeAction.value
                    val enterPressed =
                        event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.action == KeyEvent.ACTION_DOWN
                    if (actionId == action || enterPressed) {
                        currentOnImeAction.value()
                        true
                    } else {
                        false
                    }
                }
                setOnFocusChangeListener { _, hasFocus ->
                    currentOnFocusChanged.value(hasFocus)
                }
                setOnKeyListener { _, _, event ->
                    event.action == KeyEvent.ACTION_DOWN && currentOnKeyDown.value(event)
                }
                setOnClickListener {
                    requestFocus()
                    context.getSystemService(InputMethodManager::class.java)
                        ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
                handle.attach(this)
            }
        },
        update = { editor ->
            editor.isEnabled = enabled
            editor.inputType = inputType
            editor.setSelectAllOnFocus(selectAllOnFocus)
            editor.imeOptions =
                imeAction or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN
            editor.hint = hint
            editor.contentDescription = hint
            editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            editor.setTextColor(textColor.toArgb())
            editor.setHintTextColor(hintTextColor.toArgb())
            if (editor.text.toString() != value) {
                val selection = editor.selectionStart.coerceIn(0, value.length)
                editor.setText(value)
                editor.setSelection(selection)
            }
            handle.attach(editor)
        },
    )

    DisposableEffect(handle) {
        onDispose { handle.hideKeyboard() }
    }

    if (autoFocus) {
        LaunchedEffect(handle) {
            handle.requestFocus(autoFocusShowsKeyboard)
        }
    }
}

private fun requestNativeFocus(
    editor: EditText,
    showKeyboard: Boolean,
) {
    editor.requestFocus()
    if (!showKeyboard) return
    editor.post {
        if (editor.hasFocus()) {
            editor.context
                .getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
