/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>
#include "../include/dicttrie.h"

using namespace ime_pinyin;

/**
 * Build binary dictionary model. Make sure that ___BUILD_MODEL___ is defined
 * in dictdef.h.
 */
int main(int argc, char* argv[]) {
  DictTrie* dict_trie = new DictTrie();
  bool success;
  if (argc >= 3)
     success = dict_trie->build_dict(argv[1], argv[2]);
  else
     success = dict_trie->build_dict("../data/rawdict_utf16_65105_freq.txt",
                                     "../data/valid_utf16.txt");

  if (!success) {
    printf("Build dictionary unsuccessfully.\n");
    return -1;
  }
  printf("Build dictionary successfully.\n");

  // argv[3] = 输出 .dat 路径（2026-08-29 本项目扩充：设备端重建词典用）
  const char* out = (argc >= 4) ? argv[3] : "../../res/raw/dict_pinyin.dat";
  success = dict_trie->save_dict(out);

  if (success) {
    printf("Save dictionary successfully: %s\n", out);
  } else {
    printf("Save dictionary unsuccessfully.\n");
    return -1;
  }

  return 0;
}
