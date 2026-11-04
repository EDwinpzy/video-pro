package com.dongpiandi.videopro.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.app.Instrumentation;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.app.UiAutomation;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.junit.Test;
import org.junit.runner.RunWith;

/** End-to-end tests for Video Pro's accessibility input method in a separate app process. */
@RunWith(AndroidJUnit4.class)
public final class SystemDictationProbeTest {
    private static final int VOICE_SCAN_CODE = 192;
    private static final Uri PROVIDER =
            Uri.parse("content://com.dongpiandi.videopro.dictation-debug");

    private final Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();
    private final UiAutomation automation =
            instrumentation.getUiAutomation(
                    UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);

    @Test
    public void fakeRecognizerUsesRealAccessibilityKeyPathAndInsertsTwice() {
        assumeTrue(Build.VERSION.SDK_INT >= 33);
        callProvider("reset");
        callProvider("install");

        DictationProbeActivity activity = launchProbe();
        awaitReady(activity);

        String keyboardDevice = findJmgoKeyboardDevice();
        injectVoiceKey(keyboardDevice, KeyEvent.ACTION_DOWN);
        SystemClock.sleep(300);
        injectVoiceKey(keyboardDevice, KeyEvent.ACTION_UP);
        assertEquals("第一句", awaitEditorText(activity, "第一句"));

        injectVoiceKey(keyboardDevice, KeyEvent.ACTION_DOWN);
        SystemClock.sleep(300);
        injectVoiceKey(keyboardDevice, KeyEvent.ACTION_UP);
        assertEquals("第一句第二句", awaitEditorText(activity, "第一句第二句"));
    }

    @Test
    public void vendorSpeechInsertsIntoFocusedEditorWithoutVisibleAssistant() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 33);
        assumeTrue(hasPackage("com.jmgo.ai.voice"));
        callProvider("reset");

        DictationProbeActivity activity = launchProbe();
        awaitReady(activity);
        SystemClock.sleep(1_500);

        broadcastVoice("NEAR_START");
        SystemClock.sleep(700);
        AtomicReference<CountDownLatch> playback = new AtomicReference<>();
        instrumentation.runOnMainSync(
                () -> playback.set(activity.playBundledSpeech()));
        boolean stayedFocused = true;
        while (!playback.get().await(100, TimeUnit.MILLISECONDS)) {
            final boolean[] hasFocus = new boolean[1];
            instrumentation.runOnMainSync(() -> hasFocus[0] = activity.hasEditorFocus());
            stayedFocused &= hasFocus[0];
        }
        assertTrue(
                "The factory assistant took focus while the speech was playing.",
                stayedFocused);
        broadcastVoice("NEAR_STOP");

        String transcript = awaitEditorText(activity, "天气", 15_000);
        assertTrue(
                "Expected a weather transcript but received [" + transcript + "].",
                transcript.contains("天气"));
        final boolean[] hasFocus = new boolean[1];
        instrumentation.runOnMainSync(() -> hasFocus[0] = activity.hasEditorFocus());
        assertTrue(
                "The probe editor lost focus, which usually means the factory assistant opened.",
                hasFocus[0]);
        String focusedWindow = shell("dumpsys window | grep mCurrentFocus");
        assertTrue(
                "The factory assistant window still owns focus: " + focusedWindow,
                focusedWindow.contains(instrumentation.getTargetContext().getPackageName()));
    }

    private DictationProbeActivity launchProbe() {
        Intent intent =
                new Intent(Intent.ACTION_MAIN)
                        .setClassName(
                                instrumentation.getTargetContext().getPackageName(),
                                DictationProbeActivity.class.getName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return (DictationProbeActivity) instrumentation.startActivitySync(intent);
    }

    private void awaitReady(DictationProbeActivity activity) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        Bundle status = Bundle.EMPTY;
        boolean focused = false;
        while (SystemClock.uptimeMillis() < deadline) {
            status = callProvider("status");
            final boolean[] currentFocus = new boolean[1];
            instrumentation.runOnMainSync(() -> currentFocus[0] = activity.hasEditorFocus());
            focused = currentFocus[0];
            if (status.getBoolean("editor") && status.getBoolean("keyboard") && focused) {
                return;
            }
            if (focused && status.getBoolean("editor")) {
                instrumentation.runOnMainSync(activity::showKeyboard);
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError(
                "The editor or accessibility input method never became ready. service="
                        + status
                        + " focused="
                        + focused);
    }

    private String awaitEditorText(
            DictationProbeActivity activity, String expectedPrefix) {
        return awaitEditorText(activity, expectedPrefix, 5_000);
    }

    private String awaitEditorText(
            DictationProbeActivity activity, String expectedPrefix, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        String latest = "";
        while (SystemClock.uptimeMillis() < deadline) {
            final String[] current = new String[1];
            instrumentation.runOnMainSync(() -> current[0] = activity.getEditorText());
            latest = current[0];
            if (latest.contains(expectedPrefix)) {
                return latest;
            }
            SystemClock.sleep(100);
        }
        return latest;
    }

    private void injectVoiceKey(String device, int action) {
        shell(
                "sendevent " + device + " 4 4 " + VOICE_SCAN_CODE + "; "
                        + "sendevent " + device + " 1 " + VOICE_SCAN_CODE + " "
                        + (action == KeyEvent.ACTION_DOWN ? "1" : "0") + "; "
                        + "sendevent " + device + " 0 0 0");
    }

    private void broadcastVoice(String operate) {
        shell(
                "am broadcast -a com.jmgo.action.AI_VOICE "
                        + "--es operate "
                        + operate);
    }

    private String findJmgoKeyboardDevice() {
        String listing = shell("getevent -lp");
        String currentDevice = null;
        boolean matched = false;
        for (String line : listing.split("\\R")) {
            if (line.startsWith("add device ")) {
                currentDevice = line.substring(line.lastIndexOf(' ') + 1);
                matched = false;
            } else if (line.contains("name:") && line.contains("JMGO Controller Keyboard")) {
                matched = true;
            } else if (matched && line.trim().startsWith("KEY")) {
                return currentDevice;
            }
        }
        throw new AssertionError("The S621 remote keyboard input device was not found.");
    }

    private String shell(String command) {
        ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                new ParcelFileDescriptor.AutoCloseInputStream(descriptor)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        } catch (Exception error) {
            throw new AssertionError("Shell command failed: " + command, error);
        }
        return output.toString();
    }

    private boolean hasPackage(String packageName) {
        try {
            instrumentation
                    .getTargetContext()
                    .getPackageManager()
                    .getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private Bundle callProvider(String method) {
        Bundle result =
                instrumentation
                        .getTargetContext()
                        .getContentResolver()
                        .call(PROVIDER, method, null, Bundle.EMPTY);
        assertNotNull("No result from dictation provider method " + method, result);
        return result;
    }
}
