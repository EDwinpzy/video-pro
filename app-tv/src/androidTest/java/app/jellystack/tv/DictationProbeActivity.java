package app.jellystack.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

/** A separate installed package with a plain editor, used for the external system-dictation check. */
public final class DictationProbeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        EditText editor = new EditText(this);
        editor.setHint("跨应用听写测试");
        editor.setSingleLine(true);
        editor.setGravity(Gravity.CENTER_VERTICAL);
        editor.setText("");
        editor.requestFocus();

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        120,
                        Gravity.CENTER);
        params.setMarginStart(120);
        params.setMarginEnd(120);

        FrameLayout content = new FrameLayout(this);
        content.addView(editor, params);
        setContentView(content);

        main.postDelayed(
                () -> {
                    editor.requestFocus();
                    InputMethodManager inputMethodManager =
                            getSystemService(InputMethodManager.class);
                    if (inputMethodManager != null) {
                        inputMethodManager.showSoftInput(
                                editor,
                                InputMethodManager.SHOW_IMPLICIT);
                    }
                },
                400L);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
