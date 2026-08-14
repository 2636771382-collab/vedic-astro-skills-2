package com.oai.wechatreadonly;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class EvidenceStore {
    private final Context context;

    public EvidenceStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public String saveScreen(String contact, String captureId, long capturedAt,
                             String source, Bitmap bitmap) throws Exception {
        File dir = contactDir(contact);
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("cannot create evidence dir");

        String fileName = "screen_" + capturedAt + "_" + captureId + ".jpg";
        File file = new File(dir, fileName);
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 58, out)) {
                throw new IllegalStateException("jpeg compress failed");
            }
        }

        File manifest = new File(dir, "screens_manifest.jsonl");
        String line = "{" +
                "\"capture_id\":" + j(captureId) + "," +
                "\"captured_at\":" + capturedAt + "," +
                "\"source\":" + j(source) + "," +
                "\"file\":" + j("screens/" + fileName) +
                "}\n";
        try (FileOutputStream fos = new FileOutputStream(manifest, true)) {
            fos.write(line.getBytes(StandardCharsets.UTF_8));
        }
        return "screens/" + fileName;
    }

    public void deleteScreen(String contact, String evidenceFile) {
        if (evidenceFile == null || evidenceFile.isEmpty()) return;
        String name = evidenceFile.startsWith("screens/") ? evidenceFile.substring(8) : evidenceFile;
        File f = new File(contactDir(contact), name);
        if (f.exists()) f.delete();
    }

    public int countScreens(String contact) {
        File[] files = contactDir(contact).listFiles((d, name) -> name.startsWith("screen_") && name.endsWith(".jpg"));
        return files == null ? 0 : files.length;
    }

    public void clearContact(String contact) {
        deleteRecursive(contactDir(contact));
    }

    public void exportZip(String contact, ChatDbHelper db, OutputStream raw) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
            zip.putNextEntry(new ZipEntry("chat.jsonl"));
            db.exportJsonl(contact, zip);
            zip.closeEntry();

            String readme = "WeChat Readonly v0.7 evidence bundle\n" +
                    "chat.jsonl = OCR/structured records\n" +
                    "screens_manifest.jsonl = capture order and screenshot mapping\n" +
                    "screens/*.jpg = visual evidence preserving stickers, images, call cards and context\n" +
                    "captured_at is capture time, not necessarily original WeChat send time.\n";
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(readme.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            File dir = contactDir(contact);
            File manifest = new File(dir, "screens_manifest.jsonl");
            if (manifest.exists()) addFile(zip, manifest, "screens_manifest.jsonl");

            File[] screens = dir.listFiles((d, name) -> name.startsWith("screen_") && name.endsWith(".jpg"));
            if (screens != null) {
                Arrays.sort(screens, Comparator.comparing(File::getName));
                for (File f : screens) addFile(zip, f, "screens/" + f.getName());
            }
        }
    }

    private void addFile(ZipOutputStream zip, File file, String entryName) throws Exception {
        zip.putNextEntry(new ZipEntry(entryName));
        byte[] buf = new byte[64 * 1024];
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int n;
            while ((n = in.read(buf)) != -1) zip.write(buf, 0, n);
        }
        zip.closeEntry();
    }

    private File contactDir(String contact) {
        String safe = contact == null ? "unknown" : contact.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return new File(new File(context.getFilesDir(), "wechat_v07_evidence"), safe);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private static String j(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }
}
