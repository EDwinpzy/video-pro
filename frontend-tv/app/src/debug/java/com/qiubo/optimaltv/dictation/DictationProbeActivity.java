package com.qiubo.optimaltv.dictation;

import android.app.Activity;
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
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.util.concurrent.CountDownLatch;

/** Debug-only target-package editor used by the system-dictation instrumentation tests. */
public final class DictationProbeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText editor;
    private MediaPlayer speechPlayer;
    private int originalMusicVolume = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        editor = new EditText(this);
        editor.setHint("系统听写测试");
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

    public String getEditorText() {
        return editor == null ? "" : editor.getText().toString();
    }

    /**
     * Plays a bundled Chinese phrase through the TV speakers while the real system voice key is
     * held down. The device test restores the previous music volume when playback ends.
     */
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
            AssetFileDescriptor audio =
                    getAssets().openFd("dictation_zh.wav");
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
        main.removeCallbacksAndMessages(null);
        releaseSpeechPlayer();
        super.onDestroy();
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
