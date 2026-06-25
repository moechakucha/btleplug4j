// SPDX-FileCopyrightText: © 2026 moechakucha
// SPDX-License-Identifier: MIT
package moe.prwk.btleplug4j.util;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class NativeLoader {
    public static void load() {
        try {
            String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String osArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

            String os;
            String extension;
            String prefix = "lib";
            if (osName.contains("mac")) {
                os = "macos";
                extension = ".dylib";
            } else if (osName.contains("win")) {
                os = "windows";
                extension = ".dll";
                prefix = "";
            } else if (osName.contains("nux")) {
                os = "linux";
                extension = ".so";
            } else {
                throw new UnsupportedOperationException("Unsupported OS: " + osName);
            }

            String arch;
            if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                arch = "aarch64";
            } else if (osArch.contains("amd64") || osArch.contains("x86_64")) {
                arch = "x86_64";
            } else {
                throw new UnsupportedOperationException("Unsupported Arch: " + osArch);
            }

            String libName = prefix + "btleplug4j_ffi" + extension;
            String resourcePath = "/natives/" + os + "-" + arch + "/" + libName;

            try (InputStream is = NativeLoader.class.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new RuntimeException("Native library not found in JAR: " + resourcePath);
                }

                Path tempFile = Files.createTempFile("btleplug4j_ffi_", extension);
                tempFile.toFile().deleteOnExit();

                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);

                System.load(tempFile.toAbsolutePath().toString());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load native library", e);
        }
    }
}
