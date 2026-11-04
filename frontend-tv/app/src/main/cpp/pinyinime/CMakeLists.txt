# AOSP 谷歌拼音输入法解码器（Apache-2.0，源码来自 AOSP packages/inputmethods/PinyinIME，
# 镜像 github.com/ideastudios/PinyinIME commit 49aebad1，2026-08-29 引入本项目内嵌编译）。
# 仅用其 native 解码内核：搜索页软键盘拼音候选（aiqinggongyu → 爱情公寓）。
cmake_minimum_required(VERSION 3.22.1)
project(jni_pinyinime)

add_library(jni_pinyinime SHARED
        android/com_android_inputmethod_pinyin_PinyinDecoderService.cpp
        share/dictbuilder.cpp
        share/dictlist.cpp
        share/dicttrie.cpp
        share/lpicache.cpp
        share/matrixsearch.cpp
        share/mystdlib.cpp
        share/ngram.cpp
        share/pinyinime.cpp
        share/searchutility.cpp
        share/spellingtable.cpp
        share/spellingtrie.cpp
        share/splparser.cpp
        share/userdict.cpp
        share/utf16char.cpp
        share/utf16reader.cpp
        share/sync.cpp
        )

target_include_directories(jni_pinyinime PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

find_library(log-lib log)
target_link_libraries(jni_pinyinime ${log-lib})
