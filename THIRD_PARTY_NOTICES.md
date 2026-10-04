# Third-party notices

## Official 7-Zip 26.03 and adapted 7-Zip-JBinding

PPMd ZIP extraction uses the local `sevenzip` Android library. The old JitPack native dependency is removed.

- Engine source: https://github.com/ip7z/7zip/tree/0766b733fe3e06dd2a7f9a3cfbf2108ac73abd17
- JNI/Java source: https://github.com/omicronapps/7-Zip-JBinding-4Android/tree/875f38aac441f41e6eb693177e020e97971dca97 (adapted locally)
- LGPL 2.1 or later, BSD components, and the unRAR restriction. Do not use RAR decoding code to recreate the RAR compression algorithm or develop a RAR-compatible archiver.
- Corresponding modified source and build scripts are in `sevenzip/` and accompany candidate APKs as `sevenzip-source-26.03.zip`. Build with NDK r28c using `./gradlew :sevenzip:assembleRelease` to relink. Reverse engineering for debugging LGPL modifications is permitted.
- License texts are included in the module and APK `assets/licenses/sevenzip26-*`.

## XZ for Java 1.10

Used by Commons Compress for bounded 7z LZMA/LZMA2 extraction. Zero-Clause BSD; copyright and license in APK assets/licenses/xz-COPYING.txt. Source: https://github.com/tukaani-project/xz-java/tree/v1.10 .
