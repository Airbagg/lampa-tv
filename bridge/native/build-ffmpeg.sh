#!/bin/zsh
# Минимальный ffmpeg для телевизора (Android, armeabi-v7a): только чтобы
# вытащить звук серии из TorrServer и сделать моно 11025 Гц для AudioPrint.
# Стандартный MatroskaExtractor на Haier не умеет A_AC3, а почти все раздачи —
# MKV с AC3. Собирается из исходников FFmpeg 7.1 (LGPL 2.1+) с NDK r27:
#   git clone --depth 1 --branch n7.1.2 https://git.ffmpeg.org/ffmpeg.git && cd ffmpeg && ../build-ffmpeg.sh
set -euo pipefail
NDK=${NDK:-/opt/homebrew/share/android-commandlinetools/ndk/27.2.12479018}
TC=$NDK/toolchains/llvm/prebuilt/darwin-x86_64
./configure \
  --target-os=android --arch=arm --cpu=armv7-a --enable-cross-compile \
  --cc=$TC/bin/armv7a-linux-androideabi24-clang --cxx=$TC/bin/armv7a-linux-androideabi24-clang++ \
  --ar=$TC/bin/llvm-ar --nm=$TC/bin/llvm-nm --ranlib=$TC/bin/llvm-ranlib --strip=$TC/bin/llvm-strip \
  --sysroot=$TC/sysroot --extra-cflags="-O2 -fPIE -mfpu=neon -mfloat-abi=softfp" --extra-ldflags="-pie" \
  --enable-static --disable-shared --enable-small --enable-pic \
  --disable-doc --disable-ffplay --disable-ffprobe --disable-avdevice --disable-swscale --disable-postproc \
  --disable-everything --enable-network \
  --enable-protocol=http,file,pipe,tcp \
  --enable-demuxer=matroska,mov,mpegts,avi,flv,ac3,eac3,aac,mp3 \
  --enable-decoder=ac3,ac3_fixed,eac3,dca,aac,aac_fixed,mp3,mp2,opus,vorbis,flac,truehd,mlp,alac,pcm_s16le,pcm_s24le,pcm_bluray,pcm_dvd \
  --enable-parser=ac3,aac,dca,mpegaudio,opus,vorbis,flac,mlp \
  --enable-muxer=pcm_s16le --enable-encoder=pcm_s16le \
  --enable-filter=aresample,aformat,anull,pan --enable-swresample
make -j8 ffmpeg
cp ffmpeg "$(dirname "$0")/armeabi-v7a/libffmpeg.so"
