package com.dongpiandi.videopro.probe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;

/** A separate app with one real editor for cross-app system-dictation tests. */
public final class DictationProbeActivity extends Activity {
    private static volatile WeakReference<DictationProbeActivity> active =
            new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText editor;
    private MediaPlayer speechPlayer;
    private int originalMusicVolume = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        active = new WeakReference<>(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        editor = new EditText(this);
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
                    showKeyboard();
                },
                400L);
    }

    public void showKeyboard() {
        editor.requestFocus();
        InputMethodManager inputMethodManager =
                getSystemService(InputMethodManager.class);
        if (inputMethodManager != null) {
            inputMethodManager.restartInput(editor);
            inputMethodManager.showSoftInput(
                    editor,
                    InputMethodManager.SHOW_IMPLICIT);
        }
        if (getWindow().getDecorView().getWindowInsetsController() != null) {
            getWindow().getDecorView().getWindowInsetsController().show(WindowInsets.Type.ime());
        }
    }

    public String getEditorText() {
        return editor == null ? "" : editor.getText().toString();
    }

    public boolean hasEditorFocus() {
        return editor != null && editor.hasFocus() && hasWindowFocus();
    }

    public CountDownLatch playBundledSpeech() {
        final CountDownLatch finished = new CountDownLatch(1);
        try {
            AudioManager audioManager = getSystemService(AudioManager.class);
            if (audioManager != null && originalMusicVolume < 0) {
                originalMusicVolume =
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                        0);
            }
            AssetFileDescriptor audio = getAssets().openFd("dictation_zh.wav");
            speechPlayer = new MediaPlayer();
            speechPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
            speechPlayer.setDataSource(
                    audio.getFileDescriptor(),
                    audio.getStartOffset(),
                    audio.getLength());
            audio.close();
            speechPlayer.setVolume(1.0f, 1.0f);
            speechPlayer.setOnCompletionListener(
                    player -> {
                        releaseSpeechPlayer();
                        finished.countDown();
                    });
            speechPlayer.setOnErrorListener(
                    (player, what, extra) -> {
                        releaseSpeechPlayer();
                        finished.countDown();
                        return true;
                    });
            speechPlayer.prepare();
            speechPlayer.start();
        } catch (Exception error) {
            releaseSpeechPlayer();
            finished.countDown();
        }
        return finished;
    }

    @Override
    protected void onDestroy() {
        DictationProbeActivity current = active.get();
        if (current == this) {
            active = new WeakReference<>(null);
        }
        main.removeCallbacksAndMessages(null);
        releaseSpeechPlayer();
        super.onDestroy();
    }

    /** Lets adb play the checked-in Chinese sample while this Activity keeps the IME focused. */
    public static final class SpeechReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"com.dongpiandi.videopro.probe.PLAY_SPEECH".equals(intent.getAction())) {
                return;
            }
            DictationProbeActivity activity = active.get();
            if (activity != null) {
                activity.main.post(activity::playBundledSpeech);
            }
        }
    }

    private void releaseSpeechPlayer() {
        if (speechPlayer != null) {
            try {
                speechPlayer.release();
            } finally {
                speechPlayer = null;
            }
        }
        if (originalMusicVolume >= 0) {
            AudioManager audioManager = getSystemService(AudioManager.class);
            if (audioManager != null) {
                audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        originalMusicVolume,
                        0);
            }
            originalMusicVolume = -1;
        }
    }
}
