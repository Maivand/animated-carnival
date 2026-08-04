package com.mavve.myactionbar.dev;

import android.content.Context;
import android.content.SharedPreferences;

import com.mavve.myactionbar.llm.HttpJson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * The agents' project workspace: a directory of files they can create, read,
 * and edit, plus a way to execute commands against those files — the same
 * write → run → read-errors → fix loop Claude Code uses.
 *
 * A phone cannot safely compile and run arbitrary projects itself, so
 * execution goes through a sandbox runner: an HTTP endpoint (self-hosted or a
 * service like a Firecracker/Docker runner) that accepts {files, command} and
 * returns {stdout, stderr, exit_code}. Configure its URL in settings; without
 * one, agents can still write and organize code, and run_command explains
 * what is missing instead of failing silently. See docs/JARVIS.md for the
 * sandbox protocol.
 */
public class CodeWorkbench {

    private static final String PREFS = "voice_agent";
    private static final String PREF_SANDBOX_URL = "sandbox_url";
    private static final String PREF_SANDBOX_TOKEN = "sandbox_token";
    private static final int MAX_FILE_BYTES = 512 * 1024;

    private final File root;
    private final SharedPreferences prefs;

    public CodeWorkbench(Context context) {
        Context app = context.getApplicationContext();
        this.root = new File(app.getFilesDir(), "workspace");
        if (!root.exists()) {
            root.mkdirs();
        }
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String writeFile(String path, String content) throws Exception {
        File file = resolve(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return "Wrote " + bytes.length + " bytes to " + path;
    }

    public String readFile(String path) throws Exception {
        File file = resolve(path);
        if (!file.exists()) {
            return "File not found: " + path;
        }
        long length = file.length();
        if (length > MAX_FILE_BYTES) {
            return "File too large to read fully (" + length + " bytes): " + path;
        }
        byte[] bytes = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int r = in.read(bytes, read, bytes.length - read);
                if (r < 0) {
                    break;
                }
                read += r;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String listFiles() {
        StringBuilder sb = new StringBuilder();
        listInto(root, "", sb);
        return sb.length() == 0 ? "Workspace is empty." : sb.toString();
    }

    /**
     * Ship the workspace to the sandbox and run a command there. Blocking;
     * call from a background thread.
     */
    public String runCommand(String command) {
        String sandboxUrl = prefs.getString(PREF_SANDBOX_URL, "");
        if (sandboxUrl.isEmpty()) {
            return "No sandbox configured. Code was written to the workspace but cannot "
                    + "be executed on-device. Configure a sandbox runner URL in settings "
                    + "to enable the write-and-test loop.";
        }
        try {
            JSONArray files = new JSONArray();
            collectFiles(root, "", files);
            JSONObject body = new JSONObject()
                    .put("command", command)
                    .put("files", files);
            HashMap<String, String> headers = new HashMap<>();
            String token = prefs.getString(PREF_SANDBOX_TOKEN, "");
            if (!token.isEmpty()) {
                headers.put("X-Sandbox-Token", token);
            }
            JSONObject result = new JSONObject(
                    HttpJson.post(sandboxUrl, headers, body));
            return "exit_code: " + result.optInt("exit_code", -1)
                    + "\nstdout:\n" + result.optString("stdout", "")
                    + "\nstderr:\n" + result.optString("stderr", "");
        } catch (Exception e) {
            return "Sandbox error: " + e.getMessage();
        }
    }

    // ---- internals --------------------------------------------------------

    private File resolve(String path) throws Exception {
        File file = new File(root, path);
        String canonical = file.getCanonicalPath();
        if (!canonical.startsWith(root.getCanonicalPath())) {
            throw new Exception("Path escapes the workspace: " + path);
        }
        return file;
    }

    private void listInto(File dir, String prefix, StringBuilder sb) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) {
                listInto(child, name, sb);
            } else {
                sb.append(name).append(" (").append(child.length()).append(" bytes)\n");
            }
        }
    }

    private void collectFiles(File dir, String prefix, JSONArray out) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) {
                collectFiles(child, name, out);
            } else if (child.length() <= MAX_FILE_BYTES) {
                out.put(new JSONObject()
                        .put("path", name)
                        .put("content", readFile(name)));
            }
        }
    }
}
