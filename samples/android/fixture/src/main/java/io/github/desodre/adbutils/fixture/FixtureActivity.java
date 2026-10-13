package io.github.desodre.adbutils.fixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class FixtureActivity extends Activity {
    private LocalServerSocket forwardServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView content = new TextView(this);
        content.setBackgroundColor(Color.rgb(18, 52, 86));
        content.setTextColor(Color.WHITE);
        content.setGravity(Gravity.CENTER);
        content.setTextSize(24);
        content.setText("adb-utils fixture");
        content.setContentDescription("adb-utils-fixture-ready");
        setContentView(content);
        String forwardName = getIntent().getStringExtra("forward_socket");
        if (forwardName != null) {
            try {
                forwardServer = new LocalServerSocket(forwardName);
                LocalServerSocket server = forwardServer;
                startWorker("fixture-forward", () -> {
                    try (LocalSocket socket = server.accept()) {
                        echoFrame(socket);
                    }
                });
            } catch (IOException error) {
                throw new IllegalStateException("Cannot start fixture abstract socket", error);
            }
        }
        Log.i("AdbUtilsFixture", "fixture-ready:" + forwardName + ":pid=" + Process.myPid());
        handleReverseIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleReverseIntent(intent);
    }

    private void handleReverseIntent(Intent intent) {
        String reverseName = intent.getStringExtra("reverse_socket");
        if (reverseName == null) return;
        startWorker("fixture-reverse", () -> {
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(reverseName, LocalSocketAddress.Namespace.ABSTRACT));
                echoFrame(socket);
            }
        });
    }

    private static void echoFrame(LocalSocket socket) throws IOException {
        socket.setSoTimeout(10000);
        String frame = new DataInputStream(socket.getInputStream()).readUTF();
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.writeUTF(frame);
        output.flush();
    }

    private static void startWorker(String name, IoWork work) {
        Thread worker = new Thread(() -> {
            try {
                work.run();
            } catch (IOException error) {
                Log.e("AdbUtilsFixture", name + " failed", error);
            }
        }, name);
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    protected void onDestroy() {
        if (forwardServer != null) {
            try {
                forwardServer.close();
            } catch (IOException error) {
                Log.w("AdbUtilsFixture", "Cannot close fixture abstract socket", error);
            }
        }
        super.onDestroy();
    }

    private interface IoWork {
        void run() throws IOException;
    }
}
