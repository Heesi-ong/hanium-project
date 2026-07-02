package com.hanium.presentation.application.video;

import com.hanium.presentation.domain.video.type.VideoFileType;

public class VideoSignatureValidator {

    public static final int HEADER_BYTES_TO_READ = 12;

    private VideoSignatureValidator() {
    }

    public static boolean matches(VideoFileType fileType, byte[] header) {
        return switch (fileType) {
            case MP4, MOV -> hasAscii(header, 4, "ftyp");
            case AVI -> hasAscii(header, 0, "RIFF") && hasAscii(header, 8, "AVI ");
            case MKV -> hasBytes(header, 0, 0x1A, 0x45, 0xDF, 0xA3);
        };
    }

    private static boolean hasAscii(byte[] header, int offset, String expected) {
        if (header == null || header.length < offset + expected.length()) {
            return false;
        }

        for (int index = 0; index < expected.length(); index++) {
            if (header[offset + index] != (byte) expected.charAt(index)) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasBytes(byte[] header, int offset, int... expected) {
        if (header == null || header.length < offset + expected.length) {
            return false;
        }

        for (int index = 0; index < expected.length; index++) {
            if (header[offset + index] != (byte) expected[index]) {
                return false;
            }
        }

        return true;
    }
}
