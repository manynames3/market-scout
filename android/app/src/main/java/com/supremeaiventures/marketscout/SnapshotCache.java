package com.supremeaiventures.marketscout;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

final class SnapshotCache {
    private final File directory;

    SnapshotCache(File directory) { this.directory = directory; }

    synchronized JSONObject read(String name, String snapshot) {
        try {
            JSONObject entry = new JSONObject(new String(Files.readAllBytes(file(name).toPath()), StandardCharsets.UTF_8));
            if (snapshot != null && !snapshot.equals(entry.optString("snapshot"))) return null;
            return entry.optJSONObject("data");
        } catch (Exception ignored) { return null; }
    }

    synchronized void write(String name, String snapshot, JSONObject data) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            File target = file(name);
            File temporary = new File(target.getPath() + ".tmp");
            Files.write(temporary.toPath(), new JSONObject().put("snapshot", snapshot)
                    .put("data", data).toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            trim(target);
        } catch (Exception ignored) {
            // A full cache directory must not fail an otherwise successful network request.
        }
    }

    private File file(String name) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for (byte value : digest) key.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return new File(directory, key + ".json");
    }

    private void trim(File newest) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long size = 0;
        for (File file : files) size += file.length();
        File bootstrap;
        try { bootstrap = file("bootstrap"); } catch (Exception ignored) { return; }
        for (File file : files) {
            if (size <= 32L * 1024 * 1024) break;
            if (!file.equals(newest) && !file.equals(bootstrap)) {
                long length = file.length();
                if (file.delete()) size -= length;
            }
        }
    }
}
