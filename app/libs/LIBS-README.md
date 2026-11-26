# app/libs

Local decoder modules. Everything else in the playback engine is stock AndroidX Media3 1.11.1 (Apache-2.0) from Maven.

| File | Contents | Licence |
|---|---|---|
| `lib-decoder-ffmpeg-release.aar` | Media3 FFmpeg audio decoder extension over FFmpeg (LGPL 2.1 or later, built without `--enable-gpl`) | Apache-2.0 / LGPL-2.1+ |
| `lib-decoder-av1-release.aar` | Media3 dav1d AV1 decoder extension (JNI wrapper Apache-2.0, dav1d BSD-2-Clause, cpu_features Apache-2.0) | Apache-2.0 / BSD-2-Clause |

Both must be built from the same Media3 version as the Maven modules (1.11.1). A mismatch fails at runtime with
`NoSuchMethodError` the first time the decoder loads, not at compile time.

Integrity: checksums.sha256 holds the SHA-256 of each AAR; the erifyLocalLibs task (wired into preBuild) fails the build on drift. Update the file whenever an AAR is rebuilt.
Page size: the arm64-v8a and x86_64 libraries are 16 KB aligned (LOAD segment align 0x4000, checked with llvm-readelf). The 32-bit ABIs are not, and are excluded by biFilters.
